package com.northwood.inventory.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationLine;
import com.northwood.inventory.domain.StockReservationRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockReservationRepository implements StockReservationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcStockReservationRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public void save(StockReservation reservation) {
        // StockReservation is saga-driven (created from inbox handlers). The
        // current-user accessor returns null on those threads — that's the
        // desired behaviour: created_by/last_modified_by stay NULL on rows
        // the saga writes. If the reservation API ever grows a user-driven
        // creation path, the actor flows automatically.
        String actor = currentUser.currentUsername().orElse(null);
        jdbc.update("""
            INSERT INTO inventory.stock_reservation_header (
                stock_reservation_header_id, sales_order_header_id, work_order_id, warehouse_id, status, version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            reservation.id().value(),
            reservation.salesOrderId(),
            reservation.workOrderId(),
            reservation.warehouseId(),
            reservation.status().dbValue(),
            1L,
            actor, actor
        );
        for (StockReservationLine line : reservation.lines()) {
            insertLine(reservation.id().value(), line);
        }
        for (DomainEvent event : reservation.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insertLine(UUID headerId, StockReservationLine line) {
        jdbc.update("""
            INSERT INTO inventory.stock_reservation_line (
                stock_reservation_line_id, stock_reservation_header_id, sales_order_line_id, product_id,
                product_sku, product_name, requested_quantity, reserved_quantity,
                status, shortage_quantity
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            line.lineId(), headerId, line.salesOrderLineId(), line.productId(),
            line.productSku(), line.productName(), line.requestedQuantity(),
            line.reservedQuantity(), line.status().dbValue(), line.shortageQuantity()
        );
    }

    @Override
    public Optional<UUID> findLiveHeaderIdForSalesOrder(UUID salesOrderHeaderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT stock_reservation_header_id FROM inventory.stock_reservation_header
                WHERE sales_order_header_id = ? AND status <> 'released'
                """,
                UUID.class, salesOrderHeaderId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AmendableSalesOrderLine> findAmendableSalesOrderLine(UUID salesOrderHeaderId, UUID salesOrderLineId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT h.stock_reservation_header_id, l.stock_reservation_line_id, h.warehouse_id,
                       l.product_id, l.requested_quantity, l.reserved_quantity, l.status
                FROM inventory.stock_reservation_line l
                JOIN inventory.stock_reservation_header h
                  ON h.stock_reservation_header_id = l.stock_reservation_header_id
                WHERE h.sales_order_header_id = ? AND l.sales_order_line_id = ?
                  AND h.status <> 'released' AND l.status <> 'released'
                """,
                (rs, n) -> new AmendableSalesOrderLine(
                    rs.getObject("stock_reservation_header_id", UUID.class),
                    rs.getObject("stock_reservation_line_id", UUID.class),
                    rs.getObject("warehouse_id", UUID.class),
                    rs.getObject("product_id", UUID.class),
                    rs.getBigDecimal("requested_quantity"),
                    rs.getBigDecimal("reserved_quantity"),
                    rs.getString("status")
                ),
                salesOrderHeaderId, salesOrderLineId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void appendLine(UUID stockReservationHeaderId, StockReservationLine line) {
        insertLine(stockReservationHeaderId, line);
    }

    @Override
    public void updateLine(
        UUID stockReservationLineId,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal shortageQuantity,
        String status
    ) {
        jdbc.update("""
            UPDATE inventory.stock_reservation_line
            SET requested_quantity = ?, reserved_quantity = ?, shortage_quantity = ?, status = ?
            WHERE stock_reservation_line_id = ?
            """,
            requestedQuantity, reservedQuantity, shortageQuantity, status, stockReservationLineId
        );
    }

    @Override
    public void recomputeSalesOrderHeaderStatus(UUID stockReservationHeaderId) {
        jdbc.update("""
            UPDATE inventory.stock_reservation_header h
            SET status = CASE
                WHEN (SELECT COALESCE(SUM(l.reserved_quantity), 0)
                      FROM inventory.stock_reservation_line l
                      WHERE l.stock_reservation_header_id = h.stock_reservation_header_id
                        AND l.status <> 'released') = 0 THEN 'failed'
                WHEN EXISTS (SELECT 1 FROM inventory.stock_reservation_line l
                      WHERE l.stock_reservation_header_id = h.stock_reservation_header_id
                        AND l.status <> 'released' AND l.shortage_quantity > 0) THEN 'partially_reserved'
                ELSE 'reserved'
              END,
              version = version + 1
            WHERE h.stock_reservation_header_id = ?
            """,
            stockReservationHeaderId
        );
    }

    @Override
    public Optional<StockReservation> findBySalesOrderId(UUID salesOrderId) {
        try {
            UUID id = jdbc.queryForObject(
                "SELECT stock_reservation_header_id FROM inventory.stock_reservation_header WHERE sales_order_header_id = ?",
                UUID.class, salesOrderId
            );
            if (id == null) {
                return Optional.empty();
            }
            // Lines + header are not currently re-loaded as a full aggregate —
            // the slice only writes; reads of the aggregate aren't part of the
            // demo path. Returning Optional.empty for now keeps the contract
            // honest.
            return Optional.empty();
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<UUID> findActiveHeaderIdForSalesOrder(UUID salesOrderHeaderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT stock_reservation_header_id FROM inventory.stock_reservation_header
                WHERE sales_order_header_id = ? AND status IN ('reserved', 'partially_reserved')
                """,
                UUID.class, salesOrderHeaderId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<UUID> findAnyHeaderIdForWorkOrder(UUID workOrderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT stock_reservation_header_id FROM inventory.stock_reservation_header WHERE work_order_id = ?",
                UUID.class, workOrderId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<UUID> findAnyHeaderIdForSalesOrder(UUID salesOrderHeaderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT stock_reservation_header_id FROM inventory.stock_reservation_header WHERE sales_order_header_id = ?",
                UUID.class, salesOrderHeaderId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<UUID> findWarehouseIdForHeader(UUID stockReservationHeaderId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT warehouse_id FROM inventory.stock_reservation_header WHERE stock_reservation_header_id = ?",
                UUID.class, stockReservationHeaderId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<ReservedLineSnapshot> findReservedLines(UUID stockReservationHeaderId) {
        return jdbc.query(
            """
            SELECT product_id, reserved_quantity
            FROM inventory.stock_reservation_line
            WHERE stock_reservation_header_id = ?
              AND reserved_quantity > 0
            """,
            (rs, n) -> new ReservedLineSnapshot(
                rs.getObject("product_id", UUID.class),
                rs.getBigDecimal("reserved_quantity")
            ),
            stockReservationHeaderId
        );
    }

    @Override
    public void markReleased(UUID stockReservationHeaderId) {
        jdbc.update(
            "UPDATE inventory.stock_reservation_header SET status = 'released', version = version + 1 "
            + "WHERE stock_reservation_header_id = ?",
            stockReservationHeaderId
        );
    }

    @Override
    public void deleteHeaderAndLines(UUID stockReservationHeaderId) {
        jdbc.update(
            "DELETE FROM inventory.stock_reservation_line WHERE stock_reservation_header_id = ?",
            stockReservationHeaderId
        );
        jdbc.update(
            "DELETE FROM inventory.stock_reservation_header WHERE stock_reservation_header_id = ?",
            stockReservationHeaderId
        );
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
                StockReservation.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event), OutboxTraceHeaders.currentJson(),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise event " + event.eventType(), e);
        }
    }
}
