package com.northwood.inventory.application.inbox;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains {@code inventory.sales_order_line_facts}. Seeded by
 * {@link SalesOrderPlacedHandler} when {@code sales.SalesOrderPlaced} arrives;
 * read by {@code ShipmentService.post} to validate that each posted line's
 * {@code product_id} matches the originating sales-order line, and to refuse
 * unpaid prepayment orders with HTTP 409.
 *
 * <p>Defence-in-depth: without this check a buggy / malicious client could
 * decrement stock against the wrong product (no constraint catches it on the
 * way in; downstream symptoms range from silent miscount to a generic 500
 * when {@code stock_balance.on_hand_quantity >= 0} fails much later).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcSalesOrderLineFactsProjection}.
 */
public interface SalesOrderLineFactsProjection {

    /**
     * Upsert one row keyed on {@code sales_order_line_id}. ON CONFLICT-safe so
     * a redelivered {@code SalesOrderPlaced} is a no-op. {@code paymentTerms}
     * is snapshotted onto every line of the order; null (legacy events) falls
     * back to {@code on_shipment} via the column DEFAULT.
     */
    void applySalesOrderPlaced(
        UUID salesOrderHeaderId,
        UUID salesOrderLineId,
        UUID productId,
        BigDecimal orderedQuantity,
        String paymentTerms
    );

    /**
     * Re-stamp a line's {@code ordered_quantity} after a quantity amendment
     * ({@code sales.SalesOrderLineQuantityChanged}), so the over-ship cap stays
     * accurate. No-op if no facts row exists yet.
     */
    void applyLineQuantityChanged(UUID salesOrderLineId, BigDecimal newOrderedQuantity);

    /**
     * Zero a removed line's {@code ordered_quantity}
     * ({@code sales.SalesOrderLineRemoved}) so nothing can ship against it.
     * Guarded on {@code shipped_quantity = 0} (removal is gated before any ship),
     * so it never violates the {@code shipped <= ordered} CHECK. No-op otherwise.
     */
    void applyLineRemoved(UUID salesOrderLineId);

    /**
     * Atomically claim {@code quantity} of a line's outstanding ship allowance.
     * Returns {@code true} when the cumulative shipped stays within
     * {@code ordered_quantity} (the claim is recorded), {@code false} when it
     * would over-ship — or when no facts row exists. Row-locked, so concurrent
     * shipments of one line serialize and only those within the cap succeed:
     * this is the synchronous guard that prevents a double-ship from
     * over-decrementing stock. Lines with no {@code sales_order_line_id}
     * (unlinked manual shipments) are not claimed by the caller. A line a
     * cancellation already claimed ({@link #tryClaimCancellation}) is refused.
     */
    boolean tryClaimShipment(UUID salesOrderLineId, BigDecimal quantity);

    /**
     * Atomically claim cancellation of an order: mark every not-yet-shipped line
     * {@code cancelled} (so a subsequent {@link #tryClaimShipment} is refused) and
     * report whether the whole order may be cancelled. Returns {@code true} when no
     * line has shipped — the cancellation applies; {@code false} when a line has
     * already shipped — a shipment beat the cancel, so the order must proceed as
     * shipped (the caller must not release stock or ack the cancellation). This is
     * the cross-service arbiter for the cancel-vs-ship race: it row-locks the same
     * {@code sales_order_line_facts} rows the ship-claim does, so whichever of
     * {@code tryClaimCancellation} / {@code tryClaimShipment} commits first wins and
     * the other is rejected. No facts rows (cancel before placement projected) →
     * {@code true} (nothing to ship, so cancellable).
     */
    boolean tryClaimCancellation(UUID salesOrderHeaderId);

    /**
     * Flip {@code upfront_settled = true} on every line of the order. Driven by
     * {@code sales.SalesOrderUpfrontPaymentSettled} (emitted when the fulfilment
     * saga settles the up-front payment — {@code prepaid} for prepayment,
     * {@code deposit_paid} for deposit). Idempotent — the UPDATE is a no-op once
     * the flag is already true.
     */
    void applyUpfrontPaymentSettled(UUID salesOrderHeaderId);

    /**
     * Return the projected {@code product_id} for the named SO line, or empty
     * if no row has been projected yet. Callers (shipment validation) treat
     * empty as "unknown line" → reject.
     */
    Optional<UUID> findProductIdForLine(UUID salesOrderLineId);

    /**
     * Returns the (paymentTerms, upfrontSettled) pair for any line of the order,
     * or empty when no line-facts row exists yet (e.g. SalesOrderPlaced hasn't
     * been consumed). Used by ShipmentService to gate up-front-payment orders:
     * (prepayment | deposit) + !settled → HTTP 409.
     */
    Optional<UpfrontPaymentGate> findUpfrontPaymentGate(UUID salesOrderHeaderId);

    record UpfrontPaymentGate(String paymentTerms, boolean upfrontSettled) {}
}
