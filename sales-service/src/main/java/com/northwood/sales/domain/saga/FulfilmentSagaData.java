package com.northwood.sales.domain.saga;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shape of the JSON blob stored in {@code sales.sales_order_fulfilment_saga.data}.
 *
 * <p>§2.37 Slice 3 slimmed this record when the make-vs-buy decision moved into
 * inventory. Sales no longer drives manufacturing, so the work-order tracking
 * fields ({@code expectedWorkOrderCount} / {@code outstandingWorkOrderIds} /
 * {@code completedWorkOrderIds}) and the manufacturing-shortage forwarding map
 * ({@code shortageByLineNumber}) are gone. What remains:
 *
 * <ul>
 *   <li>{@link #paymentTerms} — §2.31 Slice B. The order's snapshotted
 *       commercial terms ({@code on_shipment} / {@code prepayment}), stashed at
 *       saga creation so the worker can branch at {@code started} and
 *       {@code applyCustomerPaymentReceived} can route full settlement to
 *       {@code completed} (on-shipment) or {@code prepaid} (prepayment). Null on
 *       legacy rows → treated as {@code on_shipment}.</li>
 *   <li>{@link #outstandingReplenishmentLineIds} — sales-order-line ids that are
 *       short on stock and awaiting an {@code inventory.ReplenishmentFulfilled}.
 *       Populated by {@code applyStockReserved} when reservation comes back
 *       partial/failed (inventory has already raised the replenishment in the
 *       same transaction); each arriving {@code ReplenishmentFulfilled} removes
 *       its line. When the set empties the saga re-enters
 *       {@code stock_reservation_requested} to retry reservation against the
 *       now-restocked inventory. A {@code ReplenishmentCancelled} for any of
 *       these lines rejects the order outright.</li>
 *   <li>{@link #inventoryCancellationAcked} / {@link #manufacturingCancellationAcked}
 *       — compensation acks; the saga advances to {@code compensated} once both
 *       downstream services confirm the cancel.</li>
 * </ul>
 *
 * <p>The compact constructor defaults null fields so saga rows written before a
 * field existed (or carrying just {@code {}}) deserialise cleanly.
 */
public record FulfilmentSagaData(
    Boolean inventoryCancellationAcked,
    Boolean manufacturingCancellationAcked,
    String paymentTerms,
    Set<UUID> outstandingReplenishmentLineIds
) {

    public FulfilmentSagaData {
        // Boxed Boolean (not boolean) so Jackson maps missing JSON fields to
        // null on legacy saga.data blobs without tripping
        // FAIL_ON_NULL_FOR_PRIMITIVES; the compact constructor unboxes to false.
        inventoryCancellationAcked = inventoryCancellationAcked != null && inventoryCancellationAcked;
        manufacturingCancellationAcked = manufacturingCancellationAcked != null && manufacturingCancellationAcked;
        // paymentTerms stays null on legacy rows; consumers fall back to the
        // on-shipment path (the only path that existed pre-§2.31 Slice B).
        outstandingReplenishmentLineIds = outstandingReplenishmentLineIds == null
            ? Set.of()
            : outstandingReplenishmentLineIds;
    }

    public static FulfilmentSagaData none() {
        return new FulfilmentSagaData(false, false, null, Set.of());
    }

    /** Stamp the order's commercial payment terms at saga creation. */
    public FulfilmentSagaData withPaymentTerms(String paymentTerms) {
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds)
        );
    }

    /** Record inventory's compensation ack ({@code inventory.SalesOrderCancellationApplied}). */
    public FulfilmentSagaData withInventoryCancellationAcked() {
        return new FulfilmentSagaData(
            true,
            manufacturingCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds)
        );
    }

    /** Record manufacturing's compensation ack ({@code manufacturing.SalesOrderCancellationApplied}). */
    public FulfilmentSagaData withManufacturingCancellationAcked() {
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            true,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds)
        );
    }

    /**
     * Stamp the set of sales-order-line ids the saga is awaiting an
     * {@code inventory.ReplenishmentFulfilled} for. Called by
     * {@code applyStockReserved} when reservation comes back partial/failed.
     */
    public FulfilmentSagaData withOutstandingReplenishmentLineIds(Set<UUID> lineIds) {
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            lineIds == null ? Set.of() : new LinkedHashSet<>(lineIds)
        );
    }

    /**
     * Remove a single line id from the outstanding-replenishment set when its
     * {@code ReplenishmentFulfilled} arrives. Idempotent on a line id that's
     * already absent (redelivery).
     */
    public FulfilmentSagaData withReplenishmentLineFulfilled(UUID salesOrderLineId) {
        if (!outstandingReplenishmentLineIds.contains(salesOrderLineId)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(outstandingReplenishmentLineIds);
        next.remove(salesOrderLineId);
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            next
        );
    }

    /** True when every outstanding replenishment has been fulfilled. */
    public boolean allReplenishmentLinesFulfilled() {
        return outstandingReplenishmentLineIds.isEmpty();
    }

    /** Both downstream services have acked the cancel — saga can advance to {@code compensated}. */
    public boolean bothCancellationAcksReceived() {
        return Boolean.TRUE.equals(inventoryCancellationAcked)
            && Boolean.TRUE.equals(manufacturingCancellationAcked);
    }
}
