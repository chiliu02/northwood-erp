package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcReplenishmentRequestRepository implements ReplenishmentRequestRepository {

    private static final RowMapper<ReplenishmentRequest> ROW_MAPPER = (rs, n) -> {
        Timestamp dispatchedAt = rs.getTimestamp("dispatched_at");
        Timestamp fulfilledAt = rs.getTimestamp("fulfilled_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        String kind = rs.getString("dispatched_aggregate_kind");
        return ReplenishmentRequest.reconstitute(
            ReplenishmentRequestId.of(rs.getObject("replenishment_request_id", UUID.class)),
            rs.getObject("product_id", UUID.class),
            rs.getObject("warehouse_id", UUID.class),
            rs.getBigDecimal("requested_quantity"),
            ReplenishmentRequest.TargetService.fromDb(rs.getString("target_service")),
            ReplenishmentRequest.Reason.fromDb(rs.getString("reason")),
            rs.getObject("source_sales_order_header_id", UUID.class),
            rs.getObject("source_sales_order_line_id", UUID.class),
            ReplenishmentRequest.Status.fromDb(rs.getString("status")),
            kind == null ? null : DispatchedAggregateKind.fromDb(kind),
            rs.getObject("dispatched_aggregate_id", UUID.class),
            rs.getObject("linked_purchase_order_id", UUID.class),
            dispatchedAt == null ? null : dispatchedAt.toInstant(),
            fulfilledAt == null ? null : fulfilledAt.toInstant(),
            cancelledAt == null ? null : cancelledAt.toInstant(),
            rs.getLong("version")
        );
    };

    private static final String SELECT_COLUMNS = """
        SELECT replenishment_request_id, product_id, warehouse_id,
               requested_quantity, target_service, reason,
               source_sales_order_header_id, source_sales_order_line_id, status,
               dispatched_aggregate_kind, dispatched_aggregate_id,
               linked_purchase_order_id,
               dispatched_at, fulfilled_at, cancelled_at, version
        FROM inventory.replenishment_request
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcReplenishmentRequestRepository(
        JdbcTemplate jdbc,
        ObjectMapper json,
        CurrentUserAccessor currentUser
    ) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<ReplenishmentRequest> findById(ReplenishmentRequestId id) {
        List<ReplenishmentRequest> matches = jdbc.query(
            SELECT_COLUMNS + " WHERE replenishment_request_id = ?",
            ROW_MAPPER, id.value()
        );
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    public Optional<ReplenishmentRequest> findByDispatchedAggregateId(UUID dispatchedAggregateId) {
        List<ReplenishmentRequest> matches = jdbc.query(
            SELECT_COLUMNS + " WHERE dispatched_aggregate_id = ?",
            ROW_MAPPER, dispatchedAggregateId
        );
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    public Optional<ReplenishmentRequest> findByLinkedPurchaseOrderId(UUID purchaseOrderId) {
        List<ReplenishmentRequest> matches = jdbc.query(
            SELECT_COLUMNS + " WHERE linked_purchase_order_id = ?",
            ROW_MAPPER, purchaseOrderId
        );
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    public void save(ReplenishmentRequest r) {
        String actor = currentUser.currentUsername().orElse(null);
        if (r.version() == 0L) {
            insert(r);
        } else {
            update(r);
        }
        for (DomainEvent event : r.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(ReplenishmentRequest r) {
        // The partial unique index uq_replenishment_request_open enforces the
        // one-open-per-(product, warehouse) invariant. On conflict, Postgres
        // raises a unique-violation which Spring wraps as DuplicateKeyException
        // — callers (typically ReplenishmentDetectionService) translate that
        // to a debug-logged no-op.
        jdbc.update("""
            INSERT INTO inventory.replenishment_request (
                replenishment_request_id, product_id, warehouse_id,
                requested_quantity, target_service, reason,
                source_sales_order_header_id, source_sales_order_line_id,
                status, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """,
            r.id().value(), r.productId(), r.warehouseId(),
            r.requestedQuantity(),
            r.targetService().dbValue(),
            r.reason().dbValue(),
            r.sourceSalesOrderHeaderId(),
            r.sourceSalesOrderLineId(),
            r.status().dbValue()
        );
    }

    private void update(ReplenishmentRequest r) {
        int rows = jdbc.update("""
            UPDATE inventory.replenishment_request SET
                status = ?,
                dispatched_aggregate_kind = ?,
                dispatched_aggregate_id = ?,
                linked_purchase_order_id = ?,
                dispatched_at = ?,
                fulfilled_at = ?,
                cancelled_at = ?,
                version = version + 1
            WHERE replenishment_request_id = ? AND version = ?
            """,
            r.status().dbValue(),
            r.dispatchedAggregateKind() == null ? null : r.dispatchedAggregateKind().dbValue(),
            r.dispatchedAggregateId(),
            r.linkedPurchaseOrderId(),
            r.dispatchedAt() == null ? null : Timestamp.from(r.dispatchedAt()),
            r.fulfilledAt() == null ? null : Timestamp.from(r.fulfilledAt()),
            r.cancelledAt() == null ? null : Timestamp.from(r.cancelledAt()),
            r.id().value(), r.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "ReplenishmentRequest " + r.id().value() + " was modified by another transaction"
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO inventory.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, headers, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                ReplenishmentRequest.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }
}
