package com.northwood.finance.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.finance.domain.SupplierInvoice;
import com.northwood.finance.domain.SupplierInvoiceId;
import com.northwood.finance.domain.SupplierInvoiceLine;
import com.northwood.finance.domain.SupplierInvoiceRepository;
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
public class JdbcSupplierInvoiceRepository implements SupplierInvoiceRepository {

    private static final RowMapper<SupplierInvoice> HEADER_MAPPER = (rs, n) -> SupplierInvoice.reconstitute(
        SupplierInvoiceId.of(rs.getObject("supplier_invoice_header_id", UUID.class)),
        rs.getString("internal_invoice_number"),
        rs.getString("supplier_invoice_number"),
        rs.getObject("purchase_order_header_id", UUID.class),
        rs.getObject("goods_receipt_header_id", UUID.class),
        rs.getObject("supplier_id", UUID.class),
        rs.getString("supplier_code"),
        rs.getString("supplier_name"),
        rs.getString("currency_code"),
        rs.getBigDecimal("subtotal_amount"),
        rs.getBigDecimal("tax_amount"),
        rs.getBigDecimal("total_amount"),
        rs.getString("status"),
        rs.getString("match_status"),
        List.of(),
        rs.getLong("version")
    );

    private static final RowMapper<SupplierInvoiceLine> LINE_MAPPER = (rs, n) -> new SupplierInvoiceLine(
        rs.getObject("supplier_invoice_line_id", UUID.class),
        rs.getInt("line_number"),
        rs.getObject("purchase_order_line_id", UUID.class),
        rs.getObject("goods_receipt_line_id", UUID.class),
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

    public JdbcSupplierInvoiceRepository(JdbcTemplate jdbc, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Override
    public Optional<SupplierInvoice> findById(SupplierInvoiceId id) {
        List<SupplierInvoice> matches = jdbc.query("""
            SELECT supplier_invoice_header_id, internal_invoice_number, supplier_invoice_number,
                   purchase_order_header_id, goods_receipt_header_id,
                   supplier_id, supplier_code, supplier_name,
                   currency_code, subtotal_amount, tax_amount, total_amount,
                   status, match_status, version
            FROM finance.supplier_invoice_header
            WHERE supplier_invoice_header_id = ?
            """, HEADER_MAPPER, id.value());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        SupplierInvoice stub = matches.get(0);
        List<SupplierInvoiceLine> lines = jdbc.query("""
            SELECT supplier_invoice_line_id, line_number,
                   purchase_order_line_id, goods_receipt_line_id,
                   product_id, product_sku, product_name,
                   quantity, unit_price, tax_rate, tax_amount, line_total
            FROM finance.supplier_invoice_line
            WHERE supplier_invoice_header_id = ?
            ORDER BY line_number
            """, LINE_MAPPER, id.value());
        return Optional.of(SupplierInvoice.reconstitute(
            stub.id(), stub.internalInvoiceNumber(), stub.supplierInvoiceNumber(),
            stub.purchaseOrderHeaderId(), stub.goodsReceiptHeaderId(),
            stub.supplierId(), stub.supplierCode(), stub.supplierName(),
            stub.currencyCode(),
            stub.subtotalAmount(), stub.taxAmount(), stub.totalAmount(),
            stub.status(), stub.matchStatus(),
            lines, stub.version()
        ));
    }

    @Override
    public void save(SupplierInvoice si) {
        String actor = currentUser.currentUsername().orElse(null);
        if (si.version() == 0L) {
            insert(si, actor);
        } else {
            // Manual review path (manualApprove / manualReject) updates only
            // status + match_status + approved_at; the rest is immutable.
            updateStatus(si, actor);
        }
        for (DomainEvent event : si.pullPendingEvents()) {
            writeOutbox(event, actor);
        }
    }

    private void updateStatus(SupplierInvoice si, String actor) {
        Timestamp approvedAt = SupplierInvoice.APPROVED.equals(si.status()) ? Timestamp.from(Instant.now()) : null;
        int rows = jdbc.update("""
            UPDATE finance.supplier_invoice_header
               SET status = ?, match_status = ?,
                   approved_at = COALESCE(?, approved_at),
                   version = version + 1,
                   last_modified_by = ?
             WHERE supplier_invoice_header_id = ?
            """,
            si.status(), si.matchStatus(), approvedAt, actor, si.id().value()
        );
        if (rows == 0) {
            throw new IllegalStateException(
                "supplier_invoice_header " + si.id().value() + " not found for status update"
            );
        }
    }

    @Override
    public List<SupplierInvoice> findByStatus(String status) {
        return jdbc.query("""
            SELECT supplier_invoice_header_id, internal_invoice_number, supplier_invoice_number,
                   purchase_order_header_id, goods_receipt_header_id,
                   supplier_id, supplier_code, supplier_name,
                   currency_code, subtotal_amount, tax_amount, total_amount,
                   status, match_status, version
              FROM finance.supplier_invoice_header
             WHERE status = ?
             ORDER BY internal_invoice_number
            """,
            HEADER_MAPPER, status
        );
    }

    @Override
    public List<SupplierInvoice> findAll() {
        // Headers DESC by recorded_at so the operational picker shows the
        // newest invoices first. Lines aren't loaded — they're not needed for
        // the picker label and the per-row N+1 cost adds up. The detail
        // endpoint is the right place to fetch lines if the UI needs them.
        return jdbc.query("""
            SELECT supplier_invoice_header_id, internal_invoice_number, supplier_invoice_number,
                   purchase_order_header_id, goods_receipt_header_id,
                   supplier_id, supplier_code, supplier_name,
                   currency_code, subtotal_amount, tax_amount, total_amount,
                   status, match_status, version
              FROM finance.supplier_invoice_header
             ORDER BY created_at DESC
            """,
            HEADER_MAPPER
        );
    }

    @Override
    public java.util.Optional<PaymentSnapshot> findPaymentSnapshot(UUID supplierInvoiceHeaderId) {
        try {
            return java.util.Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT supplier_id, supplier_name, purchase_order_header_id,
                       currency_code, total_amount, paid_amount, status
                FROM finance.supplier_invoice_header
                WHERE supplier_invoice_header_id = ?
                """,
                (rs, n) -> new PaymentSnapshot(
                    rs.getObject("supplier_id", UUID.class),
                    rs.getString("supplier_name"),
                    rs.getObject("purchase_order_header_id", UUID.class),
                    rs.getString("currency_code"),
                    rs.getBigDecimal("total_amount"),
                    rs.getBigDecimal("paid_amount"),
                    rs.getString("status")
                ),
                supplierInvoiceHeaderId
            ));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return java.util.Optional.empty();
        }
    }

    private void insert(SupplierInvoice si, String actor) {
        Timestamp approvedAt = SupplierInvoice.APPROVED.equals(si.status()) ? Timestamp.from(Instant.now()) : null;
        jdbc.update("""
            INSERT INTO finance.supplier_invoice_header (
                supplier_invoice_header_id, internal_invoice_number, supplier_invoice_number,
                purchase_order_header_id, goods_receipt_header_id,
                supplier_id, supplier_code, supplier_name,
                currency_code, subtotal_amount, tax_amount, total_amount,
                status, match_status, version, approved_at,
                created_by, last_modified_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            si.id().value(), si.internalInvoiceNumber(), si.supplierInvoiceNumber(),
            si.purchaseOrderHeaderId(), si.goodsReceiptHeaderId(),
            si.supplierId(), si.supplierCode(), si.supplierName(),
            si.currencyCode(), si.subtotalAmount(), si.taxAmount(), si.totalAmount(),
            si.status(), si.matchStatus(),
            1L, approvedAt,
            actor, actor
        );
        for (SupplierInvoiceLine l : si.lines()) {
            jdbc.update("""
                INSERT INTO finance.supplier_invoice_line (
                    supplier_invoice_line_id, supplier_invoice_header_id, line_number,
                    purchase_order_line_id, goods_receipt_line_id,
                    product_id, product_sku, product_name,
                    quantity, unit_price, tax_rate, tax_amount, line_total
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                l.id(), si.id().value(), l.lineNumber(),
                l.purchaseOrderLineId(), l.goodsReceiptLineId(),
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
                    event_type, event_version, payload, status, actor_user_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'pending', ?)
                """,
                event.eventId(),
                "SupplierInvoice",
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
