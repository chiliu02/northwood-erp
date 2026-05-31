package com.northwood.finance.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.finance.domain.CustomerInvoice;
import com.northwood.finance.domain.CustomerInvoiceId;
import com.northwood.finance.domain.CustomerInvoiceLine;
import com.northwood.finance.domain.CustomerInvoiceRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.messaging.OutboxTraceHeaders;
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
public class JdbcCustomerInvoiceRepository implements CustomerInvoiceRepository {

    private static final RowMapper<CustomerInvoice> HEADER_MAPPER = (rs, n) -> CustomerInvoice.reconstitute(
        CustomerInvoiceId.of(rs.getObject("customer_invoice_header_id", UUID.class)),
        rs.getString("invoice_number"),
        rs.getObject("sales_order_header_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("customer_code"),
        rs.getString("customer_name"),
        rs.getString("currency_code"),
        rs.getBigDecimal("subtotal_amount"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("total_amount"),
        CustomerInvoice.Status.fromDb(rs.getString("status")),
        CustomerInvoice.InvoiceType.fromDb(rs.getString("invoice_type")),
        List.of(),
        rs.getLong("version")
    );

    private static final RowMapper<CustomerInvoiceLine> LINE_MAPPER = (rs, n) -> new CustomerInvoiceLine(
        rs.getObject("customer_invoice_line_id", UUID.class),
        rs.getInt("line_number"),
        rs.getObject("sales_order_line_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getBigDecimal("quantity"),
        rs.getBigDecimal("unit_price"),
        rs.getBigDecimal("tax_rate"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("line_total")
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public JdbcCustomerInvoiceRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<CustomerInvoice> findById(CustomerInvoiceId id) {
        List<CustomerInvoice> matches = jdbc.query("""
            SELECT customer_invoice_header_id, invoice_number, sales_order_header_id,
                   customer_id, customer_code, customer_name,
                   currency_code, subtotal_amount, tax_amount, total_amount,
                   status, invoice_type, version
            FROM finance.customer_invoice_header
            WHERE customer_invoice_header_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        CustomerInvoice stub = matches.get(0);
        List<CustomerInvoiceLine> lines = jdbc.query("""
            SELECT customer_invoice_line_id, line_number, sales_order_line_id,
                   product_id, product_sku, product_name,
                   quantity, unit_price, tax_rate, tax_amount, line_total
            FROM finance.customer_invoice_line
            WHERE customer_invoice_header_id = ?
            ORDER BY line_number
            """, LINE_MAPPER, id.value());
        return Optional.of(CustomerInvoice.reconstitute(
            stub.id(), stub.invoiceNumber(), stub.salesOrderHeaderId(),
            stub.customerId(), stub.customerCode(), stub.customerName(),
            stub.currencyCode(),
            stub.subtotalAmount(), stub.taxAmount(), stub.totalAmount(),
            stub.status(),
            stub.invoiceType(),
            lines, stub.version()
        ));
    }

    @Override
    public List<CustomerInvoice> findAll() {
        // Header-only — list views don't render line detail. Drilling into a
        // single invoice triggers findById which loads lines.
        return jdbc.query("""
            SELECT customer_invoice_header_id, invoice_number, sales_order_header_id,
                   customer_id, customer_code, customer_name,
                   currency_code, subtotal_amount, tax_amount, total_amount,
                   status, invoice_type, version
            FROM finance.customer_invoice_header
            ORDER BY posted_at DESC NULLS LAST, customer_invoice_header_id DESC
            """, HEADER_MAPPER);
    }

    @Override
    public java.util.Optional<PaymentSnapshot> findPaymentSnapshot(java.util.UUID customerInvoiceHeaderId) {
        try {
            return java.util.Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT customer_id, customer_name, sales_order_header_id,
                       currency_code, total_amount, paid_amount, status, invoice_type
                FROM finance.customer_invoice_header
                WHERE customer_invoice_header_id = ?
                """,
                (rs, n) -> new PaymentSnapshot(
                    rs.getObject("customer_id", java.util.UUID.class),
                    rs.getString("customer_name"),
                    rs.getObject("sales_order_header_id", java.util.UUID.class),
                    rs.getString("currency_code"),
                    rs.getBigDecimal("total_amount"),
                    rs.getBigDecimal("paid_amount"),
                    CustomerInvoice.Status.fromDb(rs.getString("status")),
                    CustomerInvoice.InvoiceType.fromDb(rs.getString("invoice_type"))
                ),
                customerInvoiceHeaderId
            ));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return java.util.Optional.empty();
        }
    }

    @Override
    public java.util.Optional<ShipmentTimeInvoice> findInvoiceForShipment(java.util.UUID salesOrderHeaderId) {
        java.util.List<ShipmentTimeInvoice> rows = jdbc.query(
            """
            SELECT customer_invoice_header_id, invoice_number, invoice_type,
                   customer_name, currency_code, total_amount, revenue_recognized_at
              FROM finance.customer_invoice_header
             WHERE sales_order_header_id = ?
             ORDER BY created_at ASC
             LIMIT 1
            """,
            (rs, n) -> new ShipmentTimeInvoice(
                rs.getObject("customer_invoice_header_id", java.util.UUID.class),
                rs.getString("invoice_number"),
                CustomerInvoice.InvoiceType.fromDb(rs.getString("invoice_type")),
                rs.getString("customer_name"),
                rs.getString("currency_code"),
                rs.getBigDecimal("total_amount"),
                rs.getTimestamp("revenue_recognized_at") != null
            ),
            salesOrderHeaderId
        );
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    @Override
    public boolean markRevenueRecognized(java.util.UUID customerInvoiceHeaderId) {
        int updated = jdbc.update("""
            UPDATE finance.customer_invoice_header
               SET revenue_recognized_at = now()
             WHERE customer_invoice_header_id = ?
               AND revenue_recognized_at IS NULL
            """,
            customerInvoiceHeaderId
        );
        return updated > 0;
    }

    @Override
    public boolean markRefunded(java.util.UUID customerInvoiceHeaderId) {
        int updated = jdbc.update("""
            UPDATE finance.customer_invoice_header
               SET refunded_at = now()
             WHERE customer_invoice_header_id = ?
               AND refunded_at IS NULL
            """,
            customerInvoiceHeaderId
        );
        return updated > 0;
    }

    @Override
    public void save(CustomerInvoice ci) {
        String actor = currentUser.currentUsername().orElse(null);
        if (ci.version() == 0L) {
            insert(ci, actor);
        } else {
            throw new IllegalStateException("CustomerInvoice update path not supported in phase 5c");
        }
        for (DomainEvent event : ci.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void insert(CustomerInvoice ci, String actor) {
        Timestamp postedAt = ci.status() == CustomerInvoice.Status.POSTED ? Timestamp.from(Instant.now()) : null;
        jdbc.update("""
            INSERT INTO finance.customer_invoice_header (
                customer_invoice_header_id, invoice_number, sales_order_header_id,
                customer_id, customer_code, customer_name,
                currency_code, subtotal_amount, tax_amount, total_amount,
                status, invoice_type, version, posted_at,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            ci.id().value(), ci.invoiceNumber(), ci.salesOrderHeaderId(),
            ci.customerId(), ci.customerCode(), ci.customerName(),
            ci.currencyCode(),
            ci.subtotalAmount(), ci.taxAmount(), ci.totalAmount(),
            ci.status().dbValue(), ci.invoiceType().dbValue(), 1L, postedAt,
            actor, actor
        );
        for (CustomerInvoiceLine l : ci.lines()) {
            jdbc.update("""
                INSERT INTO finance.customer_invoice_line (
                    customer_invoice_line_id, customer_invoice_header_id, line_number,
                    sales_order_line_id,
                    product_id, product_sku, product_name,
                    quantity, unit_price, tax_rate, tax_amount, line_total
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.id(), ci.id().value(), l.lineNumber(),
                l.salesOrderLineId(),
                l.productId(), l.productSku(), l.productName(),
                l.quantity(), l.unitPrice(),
                l.taxRate() == null ? BigDecimal.ZERO : l.taxRate(),
                l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount(),
                l.lineTotal()
            );
        }
    }

    private void writeOutbox(DomainEvent event, String actor) {
        try {
            jdbc.update("""
                INSERT INTO finance.outbox_message (
                    outbox_message_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, headers, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                CustomerInvoice.AGGREGATE_TYPE,
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
