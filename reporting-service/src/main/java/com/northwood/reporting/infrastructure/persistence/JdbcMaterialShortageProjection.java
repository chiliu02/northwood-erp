package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.inbox.MaterialShortageProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMaterialShortageProjection implements MaterialShortageProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcMaterialShortageProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcMaterialShortageProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void recordShortageComponent(
        UUID materialProductId,
        String materialSku,
        String materialName,
        BigDecimal shortageQuantity,
        Instant occurredAt
    ) {
        BigDecimal qty = shortageQuantity == null ? BigDecimal.ZERO : shortageQuantity;
        jdbc.update("""
            INSERT INTO reporting.material_shortage_view (
                material_product_id, material_sku, material_name,
                required_quantity, available_quantity, shortage_quantity,
                affected_work_orders_count, affected_sales_orders_count,
                open_purchase_orders_count, incoming_purchase_quantity,
                status, updated_at
            ) VALUES (?, ?, ?, ?, 0, ?, 1, 1, 0, 0, 'open', now())
            ON CONFLICT (material_product_id) DO UPDATE SET
                material_sku = EXCLUDED.material_sku,
                material_name = EXCLUDED.material_name,
                required_quantity = material_shortage_view.required_quantity + EXCLUDED.required_quantity,
                shortage_quantity = material_shortage_view.shortage_quantity + EXCLUDED.shortage_quantity,
                affected_work_orders_count = material_shortage_view.affected_work_orders_count + 1,
                affected_sales_orders_count = material_shortage_view.affected_sales_orders_count + 1,
                status = CASE
                    WHEN material_shortage_view.status = 'resolved' THEN 'open'
                    ELSE material_shortage_view.status
                END,
                updated_at = now()
            """,
            materialProductId, materialSku, materialName,
            qty, qty
        );
    }

    @Override
    @Transactional
    public void recordPurchaseOrderLine(
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        Instant occurredAt
    ) {
        BigDecimal qty = orderedQuantity == null ? BigDecimal.ZERO : orderedQuantity;
        jdbc.update("""
            INSERT INTO reporting.material_shortage_view (
                material_product_id, material_sku, material_name,
                required_quantity, available_quantity, shortage_quantity,
                affected_work_orders_count, affected_sales_orders_count,
                open_purchase_orders_count, incoming_purchase_quantity,
                status, updated_at
            ) VALUES (?, ?, ?, 0, 0, 0, 0, 0, 1, ?, 'purchase_ordered', now())
            ON CONFLICT (material_product_id) DO UPDATE SET
                material_sku = EXCLUDED.material_sku,
                material_name = EXCLUDED.material_name,
                open_purchase_orders_count = material_shortage_view.open_purchase_orders_count + 1,
                incoming_purchase_quantity = material_shortage_view.incoming_purchase_quantity + EXCLUDED.incoming_purchase_quantity,
                status = CASE
                    WHEN material_shortage_view.status IN ('open', 'purchase_requested') THEN 'purchase_ordered'
                    ELSE material_shortage_view.status
                END,
                updated_at = now()
            """,
            productId, productSku, productName, qty
        );
    }

    @Override
    @Transactional
    public void recordReceivedLine(
        UUID productId,
        String productSku,
        String productName,
        BigDecimal receivedQuantity,
        Instant occurredAt
    ) {
        BigDecimal qty = receivedQuantity == null ? BigDecimal.ZERO : receivedQuantity;
        int rows = jdbc.update("""
            UPDATE reporting.material_shortage_view
               SET incoming_purchase_quantity = GREATEST(incoming_purchase_quantity - ?, 0),
                   shortage_quantity = GREATEST(shortage_quantity - ?, 0),
                   status = CASE
                       WHEN GREATEST(shortage_quantity - ?, 0) = 0 THEN 'resolved'
                       ELSE status
                   END,
                   open_purchase_orders_count = CASE
                       WHEN GREATEST(shortage_quantity - ?, 0) = 0 AND open_purchase_orders_count > 0
                           THEN open_purchase_orders_count - 1
                       ELSE open_purchase_orders_count
                   END,
                   updated_at = now()
             WHERE material_product_id = ?
            """,
            qty, qty, qty, qty, productId
        );
        if (rows == 0) {
            log.debug("recordReceivedLine: no shortage row for product {} ({}) — receipt not relevant",
                productSku, productId);
        }
    }
}
