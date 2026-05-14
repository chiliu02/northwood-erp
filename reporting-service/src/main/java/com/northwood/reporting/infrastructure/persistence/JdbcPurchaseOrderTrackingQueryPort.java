package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.dto.PurchaseOrderTrackingView;
import com.northwood.reporting.application.PurchaseOrderTrackingQueryPort;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchaseOrderTrackingQueryPort implements PurchaseOrderTrackingQueryPort {

    private static final String SELECT_ALL = """
        SELECT purchase_order_header_id, purchase_order_number,
               supplier_id, supplier_name,
               po_status, order_date, expected_receipt_date,
               currency_code,
               ordered_amount, received_amount, invoiced_amount, paid_amount, outstanding_amount,
               receipt_status, invoice_status, payment_status, match_status,
               last_goods_receipt_header_id, last_supplier_invoice_header_id, last_payment_id,
               updated_at
          FROM reporting.purchase_order_tracking_view
        """;

    private static final RowMapper<PurchaseOrderTrackingView> MAPPER = (rs, n) -> {
        Date orderDate = rs.getDate("order_date");
        Date expectedReceiptDate = rs.getDate("expected_receipt_date");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new PurchaseOrderTrackingView(
            rs.getObject("purchase_order_header_id", UUID.class),
            rs.getString("purchase_order_number"),
            rs.getObject("supplier_id", UUID.class),
            rs.getString("supplier_name"),
            rs.getString("po_status"),
            orderDate == null ? null : orderDate.toLocalDate(),
            expectedReceiptDate == null ? null : expectedReceiptDate.toLocalDate(),
            rs.getString("currency_code"),
            rs.getBigDecimal("ordered_amount"),
            rs.getBigDecimal("received_amount"),
            rs.getBigDecimal("invoiced_amount"),
            rs.getBigDecimal("paid_amount"),
            rs.getBigDecimal("outstanding_amount"),
            rs.getString("receipt_status"),
            rs.getString("invoice_status"),
            rs.getString("payment_status"),
            rs.getString("match_status"),
            rs.getObject("last_goods_receipt_header_id", UUID.class),
            rs.getObject("last_supplier_invoice_header_id", UUID.class),
            rs.getObject("last_payment_id", UUID.class),
            updatedAt == null ? null : updatedAt.toInstant()
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcPurchaseOrderTrackingQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PurchaseOrderTrackingView> findByPurchaseOrderId(UUID purchaseOrderHeaderId) {
        List<PurchaseOrderTrackingView> rows = jdbc.query(
            SELECT_ALL + " WHERE purchase_order_header_id = ?",
            MAPPER, purchaseOrderHeaderId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<PurchaseOrderTrackingView> findAll() {
        return jdbc.query(
            SELECT_ALL + " ORDER BY updated_at DESC NULLS LAST",
            MAPPER
        );
    }

}
