package com.northwood.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Narrow read-side queries against {@code inventory.stock_balance}.
 * Per the project's five-suffix convention, {@code *Lookup} signals "narrow
 * operational value resolution" — single number, single row.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcStockBalanceLookup}.
 */
public interface StockBalanceLookup {

    /**
     * Return {@code on_hand_quantity - reserved_quantity} for the given
     * (warehouse, product) — the free stock available for a new reservation.
     * Returns {@code 0} when the row doesn't exist.
     */
    BigDecimal findAvailableQuantity(UUID warehouseId, UUID productId);
}
