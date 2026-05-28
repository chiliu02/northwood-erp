package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.domain.replenishment.ReplenishmentRequest;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequestId;
import com.northwood.inventory.domain.replenishment.ReplenishmentRequestRepository;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcReplenishmentRequestRepository implements ReplenishmentRequestRepository {

    private static final RowMapper<ReplenishmentRequest> ROW_MAPPER = (rs, n) ->
        ReplenishmentRequest.reconstitute(
            ReplenishmentRequestId.of(rs.getObject("replenishment_request_id", UUID.class)),
            rs.getObject("product_id", UUID.class),
            rs.getObject("warehouse_id", UUID.class),
            rs.getBigDecimal("requested_quantity"),
            ReplenishmentRequest.TargetService.fromDb(rs.getString("target_service")),
            ReplenishmentRequest.Reason.fromDb(rs.getString("reason")),
            ReplenishmentRequest.Status.fromDb(rs.getString("status")),
            rs.getLong("version")
        );

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
        List<ReplenishmentRequest> matches = jdbc.query("""
            SELECT replenishment_request_id, product_id, warehouse_id,
                   requested_quantity, target_service, reason, status, version
            FROM inventory.replenishment_request
            WHERE replenishment_request_id = ?
            """, ROW_MAPPER, id.value());
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    @Override
    public void save(ReplenishmentRequest r) {
        String actor = currentUser.currentUsername().orElse(null);
        if (r.version() == 0L) {
            insert(r);
        } else {
            throw new IllegalStateException(
                "ReplenishmentRequest updates are not yet supported in Slice B; "
                + "the mark-dispatched / mark-fulfilled mutators land in Slice E"
            );
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
                requested_quantity, target_service, reason, status, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            """,
            r.id().value(), r.productId(), r.warehouseId(),
            r.requestedQuantity(),
            r.targetService().dbValue(),
            r.reason().dbValue(),
            r.status().dbValue()
        );
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO inventory.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                ReplenishmentRequest.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise " + event.eventType(), e);
        }
    }
}
