package com.northwood.inventory.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.Shipment;
import com.northwood.inventory.domain.ShipmentId;
import com.northwood.inventory.domain.ShipmentLine;
import com.northwood.inventory.domain.ShipmentRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcShipmentRepository implements ShipmentRepository {

    private static final RowMapper<Shipment> HEADER_MAPPER = (rs, n) -> Shipment.reconstitute(
        ShipmentId.of(rs.getObject("shipment_header_id", UUID.class)),
        rs.getString("shipment_number"),
        rs.getObject("sales_order_header_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("customer_name"),
        rs.getObject("warehouse_id", UUID.class),
        null,
        rs.getString("status"),
        List.of(),
        rs.getLong("version")
    );

    private static final RowMapper<ShipmentLine> LINE_MAPPER = (rs, n) -> new ShipmentLine(
        rs.getObject("shipment_line_id", UUID.class),
        rs.getObject("sales_order_line_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getBigDecimal("shipped_quantity"),
        rs.getBigDecimal("unit_cost"),
        rs.getBigDecimal("line_cost")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcShipmentRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<Shipment> findById(ShipmentId id) {
        List<Shipment> matches = jdbc.query("""
            SELECT shipment_header_id, shipment_number, sales_order_header_id,
                   customer_id, customer_name, warehouse_id, status, version
            FROM inventory.shipment_header
            WHERE shipment_header_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        Shipment stub = matches.get(0);
        List<ShipmentLine> lines = jdbc.query("""
            SELECT shipment_line_id, sales_order_line_id,
                   product_id, product_sku, product_name,
                   shipped_quantity, unit_cost, line_cost
            FROM inventory.shipment_line
            WHERE shipment_header_id = ?
            ORDER BY shipment_line_id
            """, LINE_MAPPER, id.value());
        return Optional.of(Shipment.reconstitute(
            stub.id(), stub.shipmentNumber(), stub.salesOrderHeaderId(),
            stub.customerId(), stub.customerName(),
            stub.warehouseId(), stub.warehouseCode(),
            stub.status(), lines, stub.version()
        ));
    }

    @Override
    public List<Shipment> findAllHeaders() {
        return jdbc.query("""
            SELECT shipment_header_id, shipment_number, sales_order_header_id,
                   customer_id, customer_name, warehouse_id, status, version
            FROM inventory.shipment_header
            ORDER BY created_at DESC
            """, HEADER_MAPPER);
    }

    @Override
    public void save(Shipment s) {
        String actor = currentUser.currentUsername().orElse(null);
        if (s.version() == 0L) {
            insert(s, actor);
        } else {
            throw new IllegalStateException("Shipment is post-only in phase 5c; updates not supported");
        }
        for (DomainEvent event : s.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(Shipment s, String actor) {
        Timestamp postedAt = Shipment.POSTED.equals(s.status()) ? Timestamp.from(Instant.now()) : null;
        jdbc.update("""
            INSERT INTO inventory.shipment_header (
                shipment_header_id, shipment_number, sales_order_header_id,
                customer_id, customer_name,
                warehouse_id, status, version, posted_at,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            s.id().value(), s.shipmentNumber(), s.salesOrderHeaderId(),
            s.customerId(), s.customerName(),
            s.warehouseId(), s.status(),
            1L, postedAt,
            actor, actor
        );
        for (ShipmentLine l : s.lines()) {
            jdbc.update("""
                INSERT INTO inventory.shipment_line (
                    shipment_line_id, shipment_header_id, sales_order_line_id,
                    product_id, product_sku, product_name,
                    shipped_quantity, unit_cost, line_cost
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.id(), s.id().value(), l.salesOrderLineId(),
                l.productId(), l.productSku(), l.productName(),
                l.shippedQuantity(),
                l.unitCost() == null ? BigDecimal.ZERO : l.unitCost(),
                l.lineCost() == null ? BigDecimal.ZERO : l.lineCost()
            );
        }
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
                Shipment.AGGREGATE_TYPE,
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
