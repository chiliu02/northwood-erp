package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.domain.StockAdjustment;
import com.northwood.inventory.domain.StockAdjustmentId;
import com.northwood.inventory.domain.StockAdjustmentRepository;
import com.northwood.inventory.domain.StockMovementDirection;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcStockAdjustmentRepository implements StockAdjustmentRepository {

    private static final RowMapper<StockAdjustment> MAPPER = (rs, n) -> StockAdjustment.reconstitute(
        StockAdjustmentId.of(rs.getObject("stock_adjustment_id", UUID.class)),
        rs.getString("adjustment_number"),
        rs.getObject("warehouse_id", UUID.class),
        null,
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        StockMovementDirection.fromCode(rs.getString("direction")),
        rs.getBigDecimal("quantity"),
        rs.getString("reason"),
        StockAdjustment.Status.fromCode(rs.getString("status")),
        rs.getLong("version")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcStockAdjustmentRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<StockAdjustment> findById(StockAdjustmentId id) {
        List<StockAdjustment> matches = jdbc.query("""
            SELECT stock_adjustment_id, adjustment_number, warehouse_id,
                   product_id, product_sku, product_name,
                   direction, quantity, reason, status, version
            FROM inventory.stock_adjustment
            WHERE stock_adjustment_id = ?
            """, MAPPER, id.value());
        return matches.stream().findFirst();
    }

    @Override
    public List<StockAdjustment> findAll() {
        return jdbc.query("""
            SELECT stock_adjustment_id, adjustment_number, warehouse_id,
                   product_id, product_sku, product_name,
                   direction, quantity, reason, status, version
            FROM inventory.stock_adjustment
            ORDER BY created_at DESC
            """, MAPPER);
    }

    @Override
    public void save(StockAdjustment adjustment) {
        String actor = currentUser.currentUsername().orElse(null);
        if (adjustment.version() != 0L) {
            throw new IllegalStateException("StockAdjustment is post-only; updates not supported");
        }
        jdbc.update("""
            INSERT INTO inventory.stock_adjustment (
                stock_adjustment_id, adjustment_number, warehouse_id,
                product_id, product_sku, product_name,
                direction, quantity, reason, status, version, posted_at,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            adjustment.id().value(), adjustment.adjustmentNumber(), adjustment.warehouseId(),
            adjustment.productId(), adjustment.productSku(), adjustment.productName(),
            adjustment.direction().code(), adjustment.quantity(), adjustment.reason(),
            adjustment.status().code(), 1L,
            adjustment.status() == StockAdjustment.Status.POSTED ? Timestamp.from(Instant.now()) : null,
            actor, actor
        );
        for (DomainEvent event : adjustment.pullPendingEvents()) {
            writeOutbox(event, actor);
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
                StockAdjustment.AGGREGATE_TYPE,
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
