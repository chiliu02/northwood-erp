package com.northwood.purchasing.infrastructure.saga;

import com.northwood.purchasing.domain.saga.PurchaseToPaySaga;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaPort;
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

@Repository
public class JdbcPurchaseToPaySagaAdapter implements PurchaseToPaySagaPort {

    private final JdbcTemplate jdbc;
    private final Tracer tracer;

    public JdbcPurchaseToPaySagaAdapter(JdbcTemplate jdbc, Tracer tracer) {
        this.jdbc = jdbc;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    /** §1D.3: returns current span's trace ID for the trace_id column on INSERT, or null. */
    private String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span == null) return null;
        TraceContext ctx = span.context();
        return ctx == null ? null : ctx.traceId();
    }

    @Override
    public Optional<PurchaseToPaySaga> findBySagaId(UUID sagaId) {
        return findBy("saga_id = ?", sagaId);
    }

    @Override
    public Optional<PurchaseToPaySaga> findByPurchaseOrderId(UUID purchaseOrderHeaderId) {
        return findBy("purchase_order_header_id = ?", purchaseOrderHeaderId);
    }

    private Optional<PurchaseToPaySaga> findBy(String predicate, Object value) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT saga_id, purchase_order_header_id,
                       saga_state, current_step, last_error, retry_count, next_retry_at,
                       lease_owner, lease_expires_at, version, data,
                       created_at, updated_at, completed_at
                FROM purchasing.purchase_to_pay_saga
                WHERE %s
                """.formatted(predicate),
                ROW_MAPPER, value
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<PurchaseToPaySaga> claimDue(int batchSize, Set<String> activeStates, String leaseOwner, Duration leaseTtl) {
        if (activeStates.isEmpty()) {
            return List.of();
        }
        String inList = activeStates.stream().map(s -> "'" + s.replace("'", "''") + "'")
            .reduce((a, b) -> a + "," + b).orElseThrow();
        String sql = """
            UPDATE purchasing.purchase_to_pay_saga
            SET lease_owner = ?, lease_expires_at = ?
            WHERE saga_id IN (
                SELECT saga_id FROM purchasing.purchase_to_pay_saga
                WHERE saga_state IN (%s)
                  AND next_retry_at <= now()
                  AND (lease_owner IS NULL OR lease_expires_at < now())
                ORDER BY next_retry_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING saga_id, purchase_order_header_id,
                      saga_state, current_step, last_error, retry_count, next_retry_at,
                      lease_owner, lease_expires_at, version, data,
                      created_at, updated_at, completed_at
            """.formatted(inList);
        Instant leaseExpiresAt = Instant.now().plus(leaseTtl);
        return jdbc.query(sql, ROW_MAPPER, leaseOwner, Timestamp.from(leaseExpiresAt), batchSize);
    }

    @Override
    public void save(PurchaseToPaySaga saga) {
        int rows = jdbc.update("""
            UPDATE purchasing.purchase_to_pay_saga SET
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
    }

    @Override
    public void insert(PurchaseToPaySaga saga) {
        jdbc.update("""
            INSERT INTO purchasing.purchase_to_pay_saga (
                saga_id, purchase_order_header_id,
                saga_state, current_step, last_error,
                retry_count, next_retry_at, lease_owner, lease_expires_at,
                version, data, trace_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """,
            saga.sagaId(), saga.purchaseOrderHeaderId(),
            saga.state(), saga.currentStep(), saga.lastError(),
            saga.retryCount(), Timestamp.from(saga.nextRetryAt()),
            saga.leaseOwner(), saga.leaseExpiresAt() == null ? null : Timestamp.from(saga.leaseExpiresAt()),
            1L,
            saga.dataJson(),
            currentTraceId()
        );
        saga.incrementVersion();
    }

    private static final RowMapper<PurchaseToPaySaga> ROW_MAPPER = (rs, n) -> {
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new PurchaseToPaySaga(
            rs.getObject("saga_id", UUID.class),
            rs.getObject("purchase_order_header_id", UUID.class),
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
