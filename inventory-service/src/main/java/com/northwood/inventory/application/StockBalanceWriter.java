package com.northwood.inventory.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Idempotent upsert helper for {@code inventory.stock_balance.on_hand_quantity}
 * — bumps shippable FG stock and creates the row if it doesn't yet exist.
 * Sibling of {@link StockMovementWriter}: both are called from the same flows
 * that bump on-hand quantity (production confirmation, goods receipt) so the
 * running balance and the audit ledger move together.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcStockBalanceWriter}.
 */
public interface StockBalanceWriter {

    /** Add {@code quantity} to {@code on_hand_quantity}; insert the row if it doesn't exist. */
    void bump(UUID warehouseId, UUID productId, BigDecimal quantity);

    /**
     * Apply a shipment-side decrement: drop {@code on_hand_quantity} by
     * {@code shippedQty} AND release {@code reserved_quantity} up to the
     * same amount (capped at the current reserved). Both deltas land in
     * one statement.
     */
    void decrementOnHandAndReleaseReserved(UUID warehouseId, UUID productId, BigDecimal shippedQty);

    /**
     * Atomically bump {@code reserved_quantity} by {@code quantity} only if
     * the row has enough free stock ({@code on_hand_quantity - reserved_quantity
     * >= quantity}). Returns {@code true} when the reservation succeeded,
     * {@code false} when the row didn't exist or another reservation has
     * since consumed the head-room (caller decides how to react — typically
     * mark the line as failed/partially_reserved).
     */
    boolean tryReserveOnHand(UUID warehouseId, UUID productId, BigDecimal quantity);

    /**
     * Roll back a previously-bumped reservation: decrement
     * {@code reserved_quantity} by {@code quantity}. Used by the release /
     * cancel paths when a reservation is being unwound (sales-order cancel,
     * work-order cancel, retry-cancel before re-reservation).
     */
    void releaseReserved(UUID warehouseId, UUID productId, BigDecimal quantity);
}
