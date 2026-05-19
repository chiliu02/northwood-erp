package com.northwood.sales.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderLine;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSalesOrderRepository implements SalesOrderRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcSalesOrderRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<SalesOrder> findById(SalesOrderId id) {
        List<SalesOrderHeaderRow> headers = jdbc.query(
            """
            SELECT sales_order_header_id, order_number, customer_id, customer_code, customer_name,
                   order_date, requested_delivery_date, status, currency_code, exchange_rate,
                   subtotal_amount, tax_amount, total_amount, cancelled_at, version
            FROM sales.sales_order_header WHERE sales_order_header_id = ?
            """,
            HEADER_MAPPER, id.value()
        );
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        SalesOrderHeaderRow h = headers.get(0);
        List<SalesOrderLine> lines = jdbc.query(
            """
            SELECT sales_order_line_id, line_number, product_id, product_sku, product_name,
                   ordered_quantity, reserved_quantity, manufacturing_required_quantity,
                   unit_price, tax_rate, line_status
            FROM sales.sales_order_line WHERE sales_order_header_id = ? ORDER BY line_number
            """,
            LINE_MAPPER, id.value()
        );
        return Optional.of(SalesOrder.reconstitute(
            SalesOrderId.of(h.salesOrderId), h.orderNumber, h.customerId, h.customerCode, h.customerName,
            h.orderDate, h.requestedDeliveryDate, SalesOrder.Status.fromDb(h.status), h.currencyCode, h.exchangeRate,
            h.subtotal, h.tax, h.total, h.cancelledAt, h.version, lines
        ));
    }

    @Override
    public void save(SalesOrder order) {
        String actor = currentUser.currentUsername().orElse(null);
        if (order.version() == 0) {
            insert(order, actor);
        } else {
            update(order, actor);
        }
        for (DomainEvent event : order.pullPendingEvents()) {
            writeOutbox(event, SalesOrder.AGGREGATE_TYPE, actor);
        }
    }

    private void insert(SalesOrder o, String actor) {
        jdbc.update("""
            INSERT INTO sales.sales_order_header (
                sales_order_header_id, order_number, customer_id, customer_code, customer_name,
                order_date, requested_delivery_date, status, currency_code,
                exchange_rate, exchange_rate_captured_at,
                subtotal_amount, tax_amount, total_amount, version,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?, ?, ?, ?, ?, ?)
            """,
            o.id().value(), o.orderNumber(), o.customerId(), o.customerCode(), o.customerName(),
            Date.valueOf(o.orderDate()),
            o.requestedDeliveryDate() == null ? null : Date.valueOf(o.requestedDeliveryDate()),
            o.status().dbValue(), o.currencyCode(),
            o.exchangeRate(),
            o.subtotalAmount(), o.taxAmount(), o.totalAmount(),
            1L,
            actor, actor
        );
        for (SalesOrderLine line : o.lines()) {
            jdbc.update("""
                INSERT INTO sales.sales_order_line (
                    sales_order_line_id, sales_order_header_id, line_number,
                    product_id, product_sku, product_name,
                    ordered_quantity, reserved_quantity, shipped_quantity,
                    backordered_quantity, manufacturing_required_quantity,
                    unit_price, tax_rate, tax_amount, line_total, line_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                line.lineId(), o.id().value(), line.lineNumber(),
                line.productId(), line.productSku(), line.productName(),
                line.orderedQuantity(), line.reservedQuantity(), java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, line.manufacturingRequiredQuantity(),
                line.unitPrice(), line.taxRate(), line.taxAmount(), line.lineTotal(),
                line.lineStatus().dbValue()
            );
        }
    }

    private void update(SalesOrder o, String actor) {
        int rows = jdbc.update("""
            UPDATE sales.sales_order_header SET
                status = ?,
                subtotal_amount = ?, tax_amount = ?, total_amount = ?,
                cancelled_at = ?,
                version = version + 1,
                last_modified_by = ?
            WHERE sales_order_header_id = ? AND version = ?
            """,
            o.status().dbValue(),
            o.subtotalAmount(), o.taxAmount(), o.totalAmount(),
            o.cancelledAt() == null ? null : Timestamp.from(o.cancelledAt()),
            actor,
            o.id().value(), o.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "SalesOrder " + o.id().value() + " was modified by another transaction"
            );
        }
    }

    private void writeOutbox(DomainEvent event, String aggregateType, String actor) {
        try {
            jdbc.update("""
                INSERT INTO sales.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                aggregateType,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                actor
            );
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialise event " + event.eventType(), e);
        }
    }

    private record SalesOrderHeaderRow(
        UUID salesOrderId, String orderNumber, UUID customerId, String customerCode, String customerName,
        LocalDate orderDate, LocalDate requestedDeliveryDate, String status, String currencyCode,
        java.math.BigDecimal exchangeRate, java.math.BigDecimal subtotal, java.math.BigDecimal tax,
        java.math.BigDecimal total, Instant cancelledAt, long version
    ) {}

    private static final RowMapper<SalesOrderHeaderRow> HEADER_MAPPER = (rs, n) -> {
        Date req = rs.getDate("requested_delivery_date");
        Timestamp cancelled = rs.getTimestamp("cancelled_at");
        return new SalesOrderHeaderRow(
            rs.getObject("sales_order_header_id", UUID.class),
            rs.getString("order_number"),
            rs.getObject("customer_id", UUID.class),
            rs.getString("customer_code"),
            rs.getString("customer_name"),
            rs.getDate("order_date").toLocalDate(),
            req == null ? null : req.toLocalDate(),
            rs.getString("status"),
            rs.getString("currency_code"),
            rs.getBigDecimal("exchange_rate"),
            rs.getBigDecimal("subtotal_amount"),
            rs.getBigDecimal("tax_amount"),
            rs.getBigDecimal("total_amount"),
            cancelled == null ? null : cancelled.toInstant(),
            rs.getLong("version")
        );
    };

    private static final RowMapper<SalesOrderLine> LINE_MAPPER = (rs, n) -> new SalesOrderLine(
        rs.getObject("sales_order_line_id", UUID.class),
        rs.getInt("line_number"),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getBigDecimal("ordered_quantity"),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("tax_rate"),
        rs.getBigDecimal("reserved_quantity"),
        rs.getBigDecimal("manufacturing_required_quantity"),
        SalesOrder.LineStatus.fromDb(rs.getString("line_status"))
    );
}
