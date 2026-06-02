package com.northwood.manufacturing.infrastructure.saga;

import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
import com.northwood.manufacturing.application.saga.WorkOrderSagaPort;
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

@Repository
public class JdbcWorkOrderSagaAdapter implements WorkOrderSagaPort {

    private final JdbcTemplate jdbc;
    private final Tracer tracer;

    public JdbcWorkOrderSagaAdapter(JdbcTemplate jdbc, Tracer tracer) {
        this.jdbc = jdbc;
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
    }

    /** Returns current span's trace ID for the trace_id column on INSERT, or null. */
    private String currentTraceId() {
        Span span = tracer.currentSpan();
        if (span == null) return null;
        TraceContext ctx = span.context();
        return ctx == null ? null : ctx.traceId();
    }

    @Override
    public Optional<WorkOrderSaga> findBySagaId(UUID sagaId) {
        return findBy("saga_id = ?", sagaId);
    }

    @Override
    public Optional<WorkOrderSaga> findByWorkOrderId(UUID workOrderId) {
        return findBy("work_order_id = ?", workOrderId);
    }

    private Optional<WorkOrderSaga> findBy(String predicate, Object value) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT saga_id, sales_order_header_id, sales_order_line_id, work_order_id,
                       saga_state, current_step, last_error, retry_count, next_retry_at,
                       lease_owner, lease_expires_at, version, data,
                       created_at, updated_at, completed_at
                FROM manufacturing.work_order_saga
                WHERE %s
                """.formatted(predicate),
                ROW_MAPPER, value
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<WorkOrderSaga> claimDue(int batchSize, Set<String> activeStates, String leaseOwner, Duration leaseTtl) {
        if (activeStates.isEmpty()) {
            return List.of();
        }
        String inList = activeStates.stream().map(s -> "'" + s.replace("'", "''") + "'")
            .reduce((a, b) -> a + "," + b).orElseThrow();
        String sql = """
            UPDATE manufacturing.work_order_saga
            SET lease_owner = ?, lease_expires_at = ?
            WHERE saga_id IN (
                SELECT saga_id FROM manufacturing.work_order_saga
                WHERE saga_state IN (%s)
                  AND next_retry_at <= now()
                  AND (lease_owner IS NULL OR lease_expires_at < now())
                ORDER BY next_retry_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING saga_id, sales_order_header_id, sales_order_line_id, work_order_id,
                      saga_state, current_step, last_error, retry_count, next_retry_at,
                      lease_owner, lease_expires_at, version, data,
                      created_at, updated_at, completed_at
            """.formatted(inList);
        Instant leaseExpiresAt = Instant.now().plus(leaseTtl);
        return jdbc.query(sql, ROW_MAPPER, leaseOwner, Timestamp.from(leaseExpiresAt), batchSize);
    }

    @Override
    public void update(WorkOrderSaga saga) {
        int rows = jdbc.update("""
            UPDATE manufacturing.work_order_saga SET
                saga_state = ?, current_step = ?, last_error = ?,
                retry_count = ?, next_retry_at = ?,
                lease_owner = ?, lease_expires_at = ?,
                data = ?::jsonb,
                work_order_id = ?,
                completed_at = ?,
                version = version + 1
            WHERE saga_id = ? AND version = ?
            """,
            saga.state(), saga.currentStep(), saga.lastError(),
            saga.retryCount(), Timestamp.from(saga.nextRetryAt()),
            saga.leaseOwner(), saga.leaseExpiresAt() == null ? null : Timestamp.from(saga.leaseExpiresAt()),
            saga.dataJson(),
            saga.workOrderId(),
            saga.completedAt() == null ? null : Timestamp.from(saga.completedAt()),
            saga.sagaId(), saga.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "Saga " + saga.sagaId() + " was modified by another transaction"
            );
        }
        saga.incrementVersion();
        // Milestone only on a real state advance. salesOrderHeaderId is the
        // cross-saga key — non-null for an SO-shortage-driven WO, null for a
        // pool / reorder-point WO (correctly uncorrelated to any order).
        if (saga.consumeStateAdvanced()) {
            SagaMilestone.record(tracer, WorkOrderSaga.AGGREGATE_TYPE,
                saga.sagaId(), saga.state(), saga.salesOrderHeaderId());
        }
    }

    @Override
    public void insert(WorkOrderSaga saga) {
        jdbc.update("""
            INSERT INTO manufacturing.work_order_saga (
                saga_id, sales_order_header_id, sales_order_line_id, work_order_id,
                saga_state, current_step, last_error,
                retry_count, next_retry_at, lease_owner, lease_expires_at,
                version, data, trace_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """,
            saga.sagaId(), saga.salesOrderHeaderId(), saga.salesOrderLineId(), saga.workOrderId(),
            saga.state(), saga.currentStep(), saga.lastError(),
            saga.retryCount(), Timestamp.from(saga.nextRetryAt()),
            saga.leaseOwner(), saga.leaseExpiresAt() == null ? null : Timestamp.from(saga.leaseExpiresAt()),
            1L,
            saga.dataJson(),
            currentTraceId()
        );
        saga.incrementVersion();
        // Creation is the saga's first milestone (its initial state).
        SagaMilestone.record(tracer, WorkOrderSaga.AGGREGATE_TYPE,
            saga.sagaId(), saga.state(), saga.salesOrderHeaderId());
        saga.consumeStateAdvanced();
    }

    private static final RowMapper<WorkOrderSaga> ROW_MAPPER = (rs, n) -> {
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new WorkOrderSaga(
            rs.getObject("saga_id", UUID.class),
            rs.getObject("sales_order_header_id", UUID.class),
            rs.getObject("sales_order_line_id", UUID.class),
            rs.getObject("work_order_id", UUID.class),
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
