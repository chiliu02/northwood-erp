package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter for {@link AvailableToPromiseProjection}. See the
 * interface for which event drives which column.
 */
@Repository
public class JdbcAvailableToPromiseProjection implements AvailableToPromiseProjection {

    private final JdbcTemplate jdbc;

    public JdbcAvailableToPromiseProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void recordPurchaseOrderLine(
        UUID productId, String productSku, String productName,
        BigDecimal orderedQuantity, Instant occurredAt
    ) {
        BigDecimal qty = orderedQuantity == null ? BigDecimal.ZERO : orderedQuantity;
        upsertWith(productId, productSku, productName, "incoming_from_purchase", qty);
    }

    @Override
    @Transactional
    public void recordReceivedLine(
        UUID productId, String productSku, String productName,
        BigDecimal receivedQuantity, Instant occurredAt
    ) {
        BigDecimal qty = receivedQuantity == null ? BigDecimal.ZERO : receivedQuantity;
        // GoodsReceived bumps on_hand AND reduces incoming_from_purchase.
        jdbc.update("""
            INSERT INTO reporting.available_to_promise_view (
                product_id, product_sku, product_name,
                on_hand_quantity, reserved_for_sales, reserved_for_production,
                available_quantity, incoming_from_production, incoming_from_purchase,
                stock_status, updated_at
            ) VALUES (?, ?, ?, ?, 0, 0, ?, 0, 0, 'available', now())
            ON CONFLICT (product_id) DO UPDATE SET
                product_sku = EXCLUDED.product_sku,
                product_name = EXCLUDED.product_name,
                on_hand_quantity = available_to_promise_view.on_hand_quantity + EXCLUDED.on_hand_quantity,
                incoming_from_purchase = GREATEST(available_to_promise_view.incoming_from_purchase - EXCLUDED.on_hand_quantity, 0),
                available_quantity = GREATEST(
                    (available_to_promise_view.on_hand_quantity + EXCLUDED.on_hand_quantity)
                        - available_to_promise_view.reserved_for_sales
                        - available_to_promise_view.reserved_for_production, 0),
                stock_status = CASE
                    WHEN (available_to_promise_view.on_hand_quantity + EXCLUDED.on_hand_quantity) > 0 THEN 'available'
                    WHEN GREATEST(available_to_promise_view.incoming_from_purchase - EXCLUDED.on_hand_quantity, 0) > 0
                        OR available_to_promise_view.incoming_from_production > 0 THEN 'incoming'
                    ELSE 'out_of_stock'
                END,
                updated_at = now()
            """,
            productId, productSku, productName, qty, qty
        );
    }

    @Override
    @Transactional
    public void recordSalesReservation(UUID productId, BigDecimal reservedQuantity, Instant occurredAt) {
        BigDecimal qty = reservedQuantity == null ? BigDecimal.ZERO : reservedQuantity;
        if (qty.signum() <= 0) return;
        upsertWith(productId, "(pending)", "(pending)", "reserved_for_sales", qty);
    }

    @Override
    @Transactional
    public void recordProductionReservation(UUID productId, BigDecimal reservedQuantity, Instant occurredAt) {
        BigDecimal qty = reservedQuantity == null ? BigDecimal.ZERO : reservedQuantity;
        if (qty.signum() <= 0) return;
        upsertWith(productId, "(pending)", "(pending)", "reserved_for_production", qty);
    }

    @Override
    @Transactional
    public void recordShippedLine(
        UUID productId, String productSku, String productName,
        BigDecimal shippedQuantity, Instant occurredAt
    ) {
        BigDecimal qty = shippedQuantity == null ? BigDecimal.ZERO : shippedQuantity;
        if (qty.signum() <= 0) return;
        // Shipment reduces on_hand AND reserved_for_sales (matching the same
        // single-statement decrement inventory's ShipmentService runs).
        jdbc.update("""
            INSERT INTO reporting.available_to_promise_view (
                product_id, product_sku, product_name,
                on_hand_quantity, reserved_for_sales, reserved_for_production,
                available_quantity, incoming_from_production, incoming_from_purchase,
                stock_status, updated_at
            ) VALUES (?, ?, ?, 0, 0, 0, 0, 0, 0, 'out_of_stock', now())
            ON CONFLICT (product_id) DO UPDATE SET
                product_sku = EXCLUDED.product_sku,
                product_name = EXCLUDED.product_name,
                on_hand_quantity = GREATEST(available_to_promise_view.on_hand_quantity - ?, 0),
                reserved_for_sales = GREATEST(available_to_promise_view.reserved_for_sales - LEAST(available_to_promise_view.reserved_for_sales, ?), 0),
                available_quantity = GREATEST(
                    GREATEST(available_to_promise_view.on_hand_quantity - ?, 0)
                        - GREATEST(available_to_promise_view.reserved_for_sales - LEAST(available_to_promise_view.reserved_for_sales, ?), 0)
                        - available_to_promise_view.reserved_for_production, 0),
                stock_status = CASE
                    WHEN GREATEST(available_to_promise_view.on_hand_quantity - ?, 0) > 0 THEN 'available'
                    WHEN available_to_promise_view.incoming_from_purchase > 0
                        OR available_to_promise_view.incoming_from_production > 0 THEN 'incoming'
                    ELSE 'out_of_stock'
                END,
                updated_at = now()
            """,
            productId, productSku, productName,
            qty, qty, qty, qty, qty
        );
    }

    @Override
    @Transactional
    public void recordWorkOrderPlanned(
        UUID productId, String productSku, String productName,
        BigDecimal plannedQuantity, Instant occurredAt
    ) {
        BigDecimal qty = plannedQuantity == null ? BigDecimal.ZERO : plannedQuantity;
        if (qty.signum() <= 0) return;
        upsertWith(productId, productSku, productName, "incoming_from_production", qty);
    }

    @Override
    @Transactional
    public void recordWorkOrderCompleted(
        UUID productId, String productSku,
        BigDecimal plannedQuantity, BigDecimal completedQuantity, Instant occurredAt
    ) {
        BigDecimal completed = completedQuantity == null ? BigDecimal.ZERO : completedQuantity;
        BigDecimal planned = plannedQuantity == null ? BigDecimal.ZERO : plannedQuantity;
        // Clear the planned quantity from incoming_from_production AND bump
        // on_hand by the actual completed quantity. We use planned (not
        // completed) as the decrement for incoming because that's what was
        // accumulated when the WO was created.
        jdbc.update("""
            INSERT INTO reporting.available_to_promise_view (
                product_id, product_sku, product_name,
                on_hand_quantity, reserved_for_sales, reserved_for_production,
                available_quantity, incoming_from_production, incoming_from_purchase,
                stock_status, updated_at
            ) VALUES (?, ?, '(pending)', ?, 0, 0, ?, 0, 0, 'available', now())
            ON CONFLICT (product_id) DO UPDATE SET
                product_sku = EXCLUDED.product_sku,
                on_hand_quantity = available_to_promise_view.on_hand_quantity + EXCLUDED.on_hand_quantity,
                incoming_from_production = GREATEST(available_to_promise_view.incoming_from_production - ?, 0),
                available_quantity = GREATEST(
                    (available_to_promise_view.on_hand_quantity + EXCLUDED.on_hand_quantity)
                        - available_to_promise_view.reserved_for_sales
                        - available_to_promise_view.reserved_for_production, 0),
                stock_status = CASE
                    WHEN (available_to_promise_view.on_hand_quantity + EXCLUDED.on_hand_quantity) > 0 THEN 'available'
                    WHEN available_to_promise_view.incoming_from_purchase > 0
                        OR GREATEST(available_to_promise_view.incoming_from_production - ?, 0) > 0 THEN 'incoming'
                    ELSE 'out_of_stock'
                END,
                updated_at = now()
            """,
            productId, productSku, completed, completed, planned, planned
        );
    }

    @Override
    @Transactional
    public void recordProductCreated(UUID productId, String sku, String name, Instant occurredAt) {
        jdbc.update("""
            INSERT INTO reporting.available_to_promise_view (
                product_id, product_sku, product_name,
                on_hand_quantity, reserved_for_sales, reserved_for_production,
                available_quantity, incoming_from_production, incoming_from_purchase,
                stock_status, updated_at
            ) VALUES (?, ?, ?, 0, 0, 0, 0, 0, 0, 'out_of_stock', now())
            ON CONFLICT (product_id) DO UPDATE SET
                product_sku = EXCLUDED.product_sku,
                product_name = EXCLUDED.product_name,
                updated_at = now()
            """,
            productId, sku, name
        );
    }

    /**
     * Single-column accumulator with available_quantity + stock_status
     * recompute. {@code column} must be one of the projection's accumulator
     * columns (validated by caller via static dispatch — never user input).
     */
    private void upsertWith(UUID productId, String productSku, String productName,
                             String column, BigDecimal delta) {
        String sql = ("""
            INSERT INTO reporting.available_to_promise_view (
                product_id, product_sku, product_name,
                on_hand_quantity, reserved_for_sales, reserved_for_production,
                available_quantity, incoming_from_production, incoming_from_purchase,
                stock_status, updated_at
            ) VALUES (?, ?, ?, 0, 0, 0, 0, 0, 0, 'unknown', now())
            ON CONFLICT (product_id) DO UPDATE SET
                product_sku = CASE
                    WHEN available_to_promise_view.product_sku = '(pending)' THEN EXCLUDED.product_sku
                    WHEN EXCLUDED.product_sku <> '(pending)' THEN EXCLUDED.product_sku
                    ELSE available_to_promise_view.product_sku
                END,
                product_name = CASE
                    WHEN available_to_promise_view.product_name = '(pending)' THEN EXCLUDED.product_name
                    WHEN EXCLUDED.product_name <> '(pending)' THEN EXCLUDED.product_name
                    ELSE available_to_promise_view.product_name
                END,
                COL = available_to_promise_view.COL + ?,
                available_quantity = GREATEST(
                    available_to_promise_view.on_hand_quantity
                        - (available_to_promise_view.reserved_for_sales + CASE WHEN 'COL' = 'reserved_for_sales' THEN ? ELSE 0 END)
                        - (available_to_promise_view.reserved_for_production + CASE WHEN 'COL' = 'reserved_for_production' THEN ? ELSE 0 END),
                    0),
                stock_status = CASE
                    WHEN available_to_promise_view.on_hand_quantity > 0 THEN 'available'
                    WHEN (available_to_promise_view.incoming_from_purchase + CASE WHEN 'COL' = 'incoming_from_purchase' THEN ? ELSE 0 END) > 0
                        OR (available_to_promise_view.incoming_from_production + CASE WHEN 'COL' = 'incoming_from_production' THEN ? ELSE 0 END) > 0 THEN 'incoming'
                    ELSE 'out_of_stock'
                END,
                updated_at = now()
            """).replace("COL", column);
        jdbc.update(sql,
            productId, productSku, productName, delta, delta, delta, delta, delta);
    }
}
