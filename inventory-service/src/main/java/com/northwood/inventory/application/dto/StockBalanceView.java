package com.northwood.inventory.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-side view of an {@code inventory.stock_balance} row — the on-hand /
 * reserved / available triple a stock-adjustment screen shows (and previews
 * the after-adjustment values from). {@code available} is the stored generated
 * column {@code on_hand - reserved}.
 */
public record StockBalanceView(
    UUID warehouseId,
    UUID productId,
    BigDecimal onHand,
    BigDecimal reserved,
    BigDecimal available
) {
    /** Zeroed view for a (warehouse, product) with no stock_balance row yet. */
    public static StockBalanceView empty(UUID warehouseId, UUID productId) {
        return new StockBalanceView(warehouseId, productId,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
