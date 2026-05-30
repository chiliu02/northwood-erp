package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.StockBalanceView;
import java.math.BigDecimal;
import java.util.Optional;
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

    /**
     * Return the full on-hand / reserved / available triple for the given
     * (warehouse, product) — what the stock-adjustment screen displays and
     * previews from. Empty when the row doesn't exist.
     */
    Optional<StockBalanceView> findBalance(UUID warehouseId, UUID productId);
}
