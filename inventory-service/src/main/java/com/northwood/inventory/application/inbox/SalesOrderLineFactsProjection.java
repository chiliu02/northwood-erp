package com.northwood.inventory.application.inbox;

import java.util.Optional;
import java.util.UUID;

/**
 * Maintains {@code inventory.sales_order_line_facts}. Seeded by
 * {@link SalesOrderPlacedHandler} when {@code sales.SalesOrderPlaced} arrives;
 * read by {@code ShipmentService.post} to validate that each posted line's
 * {@code product_id} matches the originating sales-order line, and (§2.31
 * Slice C) to refuse unpaid prepayment orders with HTTP 409.
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
     * a redelivered {@code SalesOrderPlaced} is a no-op. §2.31 Slice C:
     * {@code paymentTerms} is snapshotted onto every line of the order; null
     * (legacy events) falls back to {@code on_shipment} via the column DEFAULT.
     */
    void applySalesOrderPlaced(
        UUID salesOrderHeaderId,
        UUID salesOrderLineId,
        UUID productId,
        String paymentTerms
    );

    /**
     * §2.31 Slice C. Flip {@code prepayment_settled = true} on every line of
     * the order. Driven by {@code sales.SalesOrderPrepaymentSettled} (emitted
     * when the fulfilment saga reaches {@code prepaid}). Idempotent — the
     * UPDATE is a no-op once the flag is already true.
     */
    void applyPrepaymentSettled(UUID salesOrderHeaderId);

    /**
     * Return the projected {@code product_id} for the named SO line, or empty
     * if no row has been projected yet. Callers (shipment validation) treat
     * empty as "unknown line" → reject.
     */
    Optional<UUID> findProductIdForLine(UUID salesOrderLineId);

    /**
     * §2.31 Slice C. Returns the (paymentTerms, prepaymentSettled) pair for
     * any line of the order, or empty when no line-facts row exists yet
     * (e.g. SalesOrderPlaced hasn't been consumed). Used by ShipmentService
     * to gate prepayment orders: prepayment + !settled → HTTP 409.
     */
    Optional<PrepaymentGate> findPrepaymentGate(UUID salesOrderHeaderId);

    record PrepaymentGate(String paymentTerms, boolean prepaymentSettled) {}
}
