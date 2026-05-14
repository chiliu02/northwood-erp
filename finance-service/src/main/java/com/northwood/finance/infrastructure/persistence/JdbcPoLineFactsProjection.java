package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.inbox.PoLineFactsProjection;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPoLineFactsProjection implements PoLineFactsProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcPoLineFactsProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcPoLineFactsProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyPoCreated(
        UUID purchaseOrderHeaderId,
        UUID supplierId,
        String supplierName,
        String currencyCode,
        UUID purchaseOrderLineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice
    ) {
        jdbc.update("""
            INSERT INTO finance.purchase_order_line_facts (
                purchase_order_line_id, purchase_order_header_id,
                supplier_id, supplier_name, currency_code,
                product_id, product_sku, product_name,
                ordered_quantity, unit_price
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (purchase_order_line_id) DO UPDATE SET
                purchase_order_header_id = EXCLUDED.purchase_order_header_id,
                supplier_id = EXCLUDED.supplier_id,
                supplier_name = EXCLUDED.supplier_name,
                currency_code = EXCLUDED.currency_code,
                product_id = EXCLUDED.product_id,
                product_sku = EXCLUDED.product_sku,
                product_name = EXCLUDED.product_name,
                ordered_quantity = EXCLUDED.ordered_quantity,
                unit_price = EXCLUDED.unit_price
            """,
            purchaseOrderLineId, purchaseOrderHeaderId,
            supplierId, supplierName, currencyCode == null ? "AUD" : currencyCode,
            productId, productSku, productName,
            orderedQuantity, unitPrice == null ? BigDecimal.ZERO : unitPrice
        );
        log.debug("seeded po_line_facts for line={} (po={}, sku={}, qty={}, unit={})",
            purchaseOrderLineId, purchaseOrderHeaderId, productSku, orderedQuantity, unitPrice);
    }

    @Override
    @Transactional
    public void applyGoodsReceived(UUID purchaseOrderLineId, BigDecimal receivedQuantity) {
        if (purchaseOrderLineId == null || receivedQuantity == null || receivedQuantity.signum() <= 0) {
            return;
        }
        int rows = jdbc.update("""
            UPDATE finance.purchase_order_line_facts
               SET received_quantity = received_quantity + ?
             WHERE purchase_order_line_id = ?
            """,
            receivedQuantity, purchaseOrderLineId
        );
        if (rows == 0) {
            log.warn("po_line_facts row missing for purchase_order_line_id={}; receipt of {} dropped (PO event may not have arrived yet)",
                purchaseOrderLineId, receivedQuantity);
        } else {
            log.debug("bumped received_quantity by {} on po_line_facts {}", receivedQuantity, purchaseOrderLineId);
        }
    }

    @Override
    @Transactional
    public void bumpInvoiced(UUID purchaseOrderLineId, BigDecimal invoicedQuantity) {
        if (purchaseOrderLineId == null || invoicedQuantity == null || invoicedQuantity.signum() <= 0) {
            return;
        }
        jdbc.update("""
            UPDATE finance.purchase_order_line_facts
               SET invoiced_quantity = invoiced_quantity + ?
             WHERE purchase_order_line_id = ?
            """,
            invoicedQuantity, purchaseOrderLineId
        );
    }

    @Override
    public LineFacts findByLineId(UUID purchaseOrderLineId) {
        return jdbc.query("""
            SELECT purchase_order_line_id, purchase_order_header_id,
                   supplier_id, supplier_name, currency_code,
                   product_id, product_sku, product_name,
                   ordered_quantity, unit_price, received_quantity, invoiced_quantity
            FROM finance.purchase_order_line_facts
            WHERE purchase_order_line_id = ?
            """,
            (rs, n) -> new LineFacts(
                rs.getObject("purchase_order_line_id", UUID.class),
                rs.getObject("purchase_order_header_id", UUID.class),
                rs.getObject("supplier_id", UUID.class),
                rs.getString("supplier_name"),
                rs.getString("currency_code"),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_sku"),
                rs.getString("product_name"),
                rs.getBigDecimal("ordered_quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("received_quantity"),
                rs.getBigDecimal("invoiced_quantity")
            ),
            purchaseOrderLineId
        ).stream().findFirst().orElse(null);
    }
}
