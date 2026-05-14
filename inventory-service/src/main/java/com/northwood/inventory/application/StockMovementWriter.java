package com.northwood.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Audit-trail writer for {@code inventory.stock_movement}. The schema's
 * {@code stock_movement} table is partitioned by {@code movement_date};
 * each call writes one immutable audit row in the same transaction as the
 * balance update so the audit trail and the running balance stay consistent.
 *
 * <p>Called from each place that mutates {@code stock_balance.on_hand_quantity}:
 * goods-receipt service (purchase receipt, in), shipment service (sales
 * shipment, out), and the production-confirmation handler in inventory's
 * inbox (finished-goods receipt, in).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcStockMovementWriter}.
 */
public interface StockMovementWriter {

    void record(
        UUID warehouseId,
        UUID productId,
        String productSku,
        String productName,
        String movementType,
        String direction,
        BigDecimal quantity,
        BigDecimal unitCost,
        String sourceType,
        UUID sourceId,
        UUID sourceLineId
    );
}
