package com.northwood.sales.infrastructure.saga;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaPort;
import com.northwood.shared.application.saga.SagaMilestone;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JDBC-backed saga repository for {@code sales.sales_order_fulfilment_saga}.
 * The {@link #claimDue} routine selects rows whose state is currently active
 * and whose lease is free or expired, then stamps a fresh lease so a sibling
 * worker won't pick the same row in the next tick. Saves enforce the
 * optimistic-concurrency rule via {@code WHERE saga_id = ? AND version = ?}.
 */
@Repository
public class JdbcSalesOrderFulfilmentSagaAdapter implements SalesOrderFulfilmentSagaPort {

    private final JdbcTemplate jdbc;
    private final Tracer tracer;

    public JdbcSalesOrderFulfilmentSagaAdapter(JdbcTemplate jdbc, Tracer tracer) {
        this.jdbc = jdbc;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    /**
     * Returns the current span's trace ID (32 hex chars) for §1D.3's
     * {@code trace_id} column, or {@code null} when no span is active (unit
     * tests, dev profile without Kafka). Captured at INSERT only — saga
     * transitions never overwrite the original trace.
     */
    private String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span == null) return null;
        TraceContext ctx = span.context();
        return ctx == null ? null : ctx.traceId();
    }

    @Override
    public Optional<SalesOrderFulfilmentSaga> findBySalesOrderId(UUID salesOrderId) {
        return findBy("sales_order_header_id = ?", salesOrderId);
    }

    @Override
    public Optional<SalesOrderFulfilmentSaga> findBySagaId(UUID sagaId) {
        return findBy("saga_id = ?", sagaId);
    }

    private Optional<SalesOrderFulfilmentSaga> findBy(String predicate, Object value) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT saga_id, sales_order_header_id, saga_state, current_step, last_error,
                       retry_count, next_retry_at, lease_owner, lease_expires_at,
                       version, data, created_at, updated_at, completed_at
                FROM sales.sales_order_fulfilment_saga
                WHERE %s
                """.formatted(predicate),
                ROW_MAPPER, value
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<SalesOrderFulfilmentSaga> claimDue(int batchSize, Set<String> activeStates, String leaseOwner, Duration leaseTtl) {
        if (activeStates.isEmpty()) {
            return List.of();
        }
        String inList = activeStates.stream().map(s -> "'" + s.replace("'", "''") + "'")
            .reduce((a, b) -> a + "," + b).orElseThrow();
        String sql = """
            UPDATE sales.sales_order_fulfilment_saga
            SET lease_owner = ?, lease_expires_at = ?
            WHERE saga_id IN (
                SELECT saga_id FROM sales.sales_order_fulfilment_saga
                WHERE saga_state IN (%s)
                  AND next_retry_at <= now()
                  AND (lease_owner IS NULL OR lease_expires_at < now())
                ORDER BY next_retry_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING saga_id, sales_order_header_id, saga_state, current_step, last_error,
                      retry_count, next_retry_at, lease_owner, lease_expires_at,
                      version, data, created_at, updated_at, completed_at
            """.formatted(inList);
        Instant leaseExpiresAt = Instant.now().plus(leaseTtl);
        return jdbc.query(sql, ROW_MAPPER, leaseOwner, Timestamp.from(leaseExpiresAt), batchSize);
    }

    @Override
    public void update(SalesOrderFulfilmentSaga saga) {
        int rows = jdbc.update("""
            UPDATE sales.sales_order_fulfilment_saga SET
                saga_state = ?, current_step = ?, last_error = ?,
                retry_count = ?, next_retry_at = ?,
                lease_owner = ?, lease_expires_at = ?,
                data = ?::jsonb,
                completed_at = ?,
                version = version + 1
            WHERE saga_id = ? AND version = ?
            """,
            saga.state(), saga.currentStep(), saga.lastError(),
            saga.retryCount(), Timestamp.from(saga.nextRetryAt()),
            saga.leaseOwner(), saga.leaseExpiresAt() == null ? null : Timestamp.from(saga.leaseExpiresAt()),
            saga.dataJson(),
            saga.completedAt() == null ? null : Timestamp.from(saga.completedAt()),
            saga.sagaId(), saga.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "Saga " + saga.sagaId() + " was modified by another transaction"
            );
        }
        saga.incrementVersion();
        // §1D.9: record a saga-overview milestone only when this save advanced
        // the state (transitionTo) — not on data-only updates or retry reschedules.
        if (saga.consumeStateAdvanced()) {
            SagaMilestone.record(tracer, SalesOrderFulfilmentSaga.AGGREGATE_TYPE,
                saga.sagaId(), saga.state(), saga.salesOrderId());
        }
    }

    @Override
    public void insert(SalesOrderFulfilmentSaga saga) {
        jdbc.update("""
            INSERT INTO sales.sales_order_fulfilment_saga (
                saga_id, sales_order_header_id, saga_state, current_step, last_error,
                retry_count, next_retry_at, lease_owner, lease_expires_at,
                version, data, trace_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """,
            saga.sagaId(), saga.salesOrderId(), saga.state(), saga.currentStep(), saga.lastError(),
            saga.retryCount(), Timestamp.from(saga.nextRetryAt()),
            saga.leaseOwner(), saga.leaseExpiresAt() == null ? null : Timestamp.from(saga.leaseExpiresAt()),
            1L,
            saga.dataJson(),
            currentTraceId()
        );
        saga.incrementVersion();
        // §1D.9: creation is the saga's first milestone (its initial state).
        SagaMilestone.record(tracer, SalesOrderFulfilmentSaga.AGGREGATE_TYPE,
            saga.sagaId(), saga.state(), saga.salesOrderId());
        saga.consumeStateAdvanced();
    }

    private static final RowMapper<SalesOrderFulfilmentSaga> ROW_MAPPER = (rs, n) -> {
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new SalesOrderFulfilmentSaga(
            rs.getObject("saga_id", UUID.class),
            rs.getObject("sales_order_header_id", UUID.class),
            rs.getString("saga_state"),
            rs.getString("current_step"),
            rs.getString("last_error"),
            rs.getInt("retry_count"),
            rs.getTimestamp("next_retry_at").toInstant(),
            rs.getString("lease_owner"),
            leaseExpiresAt == null ? null : leaseExpiresAt.toInstant(),
            rs.getLong("version"),
            rs.getString("data"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            completedAt == null ? null : completedAt.toInstant()
        );
    };
}
