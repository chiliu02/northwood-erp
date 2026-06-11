package com.northwood.sales.domain.saga;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Shape of the JSON blob stored in {@code sales.sales_order_fulfilment_saga.data}.
 *
 * <p>Slimmed when the make-vs-buy decision moved into inventory. Sales no longer
 * drives manufacturing, so the work-order tracking fields
 * ({@code expectedWorkOrderCount} / {@code outstandingWorkOrderIds} /
 * {@code completedWorkOrderIds}) and the manufacturing-shortage forwarding map
 * ({@code shortageByLineNumber}) are gone. What remains:
 *
 * <ul>
 *   <li>{@link #paymentTerms} — the order's snapshotted commercial terms
 *       ({@code on_shipment} / {@code prepayment} / {@code deposit} /
 *       {@code cash_on_delivery}), stashed at saga creation so the worker can
 *       branch at {@code started} and {@code applyCustomerPaymentReceived} can
 *       route the up-front payment to the {@code awaiting_prepayment} gate. Null
 *       on legacy rows → treated as {@code on_shipment}.</li>
 *   <li>{@link #outstandingReplenishmentLineIds} — sales-order-line ids that are
 *       short on stock and awaiting an {@code inventory.ReplenishmentFulfilled}.
 *       Populated by {@code applyStockReserved} when reservation comes back
 *       partial/failed (inventory has already raised the replenishment in the
 *       same transaction); each arriving {@code ReplenishmentFulfilled} removes
 *       its line. When the set empties the saga re-enters
 *       {@code stock_reservation_requested} to retry reservation against the
 *       now-restocked inventory. A {@code ReplenishmentCancelled} for any of
 *       these lines rejects the order outright.</li>
 *   <li>{@link #inventoryCancellationAcked} — the compensation ack; the saga
 *       advances to {@code compensated} once inventory confirms the cancel.
 *       The manufacturing leg of the compensation gate was retired — no work
 *       order is bound to a sales order — so inventory is now the sole
 *       compensation contract.</li>
 *   <li>{@link #requestedDeliveryDate} — the order's need-by date (ISO-8601
 *       {@code yyyy-MM-dd}), stamped at saga creation so the worker can compute
 *       the planning-time-fence release date ({@code need-by − max line fence})
 *       and defer the stock reservation until then. String (not {@code LocalDate})
 *       to keep the blob serialisable without a Jackson date module — mirrors
 *       {@code paymentTerms}. Null on legacy rows / orders with no requested
 *       date → no fence gating (reserve immediately).</li>
 *   <li>{@link #orderShipped} / {@link #orderSettled} — the two flags of the
 *       <b>completion gate</b>. When the saga's post-supply ship → invoice → pay
 *       leg stopped being modelled as per-milestone states, completion became a
 *       derived meet: the saga sits at {@code supply_secured} and transitions to
 *       its {@code completed} terminal once both are true. {@code orderShipped}
 *       latches on {@code inventory.ShipmentPosted} with {@code orderFullyShipped};
 *       {@code orderSettled} latches on {@code finance.CustomerPaymentReceived}
 *       with {@code orderFullySettled} (every invoice for the order paid — for a
 *       deposit order that means deposit <em>and</em> balance). The two events
 *       carry different partition keys and can arrive in either order, so the
 *       flags are set independently and the gate is checked on each — naturally
 *       race-tolerant. Boxed {@code Boolean} → legacy rows deserialise as null →
 *       false.</li>
 * </ul>
 *
 * <p>The compact constructor defaults null fields so saga rows written before a
 * field existed (or carrying just {@code {}}) deserialise cleanly.
 */
public record FulfilmentSagaData(
    Boolean inventoryCancellationAcked,
    String paymentTerms,
    Set<UUID> outstandingReplenishmentLineIds,
    Boolean sawNonPeggedReplenishment,
    String requestedDeliveryDate,
    Boolean orderShipped,
    Boolean orderSettled
) {

    public FulfilmentSagaData {
        // Boxed Boolean (not boolean) so Jackson maps missing JSON fields to
        // null on legacy saga.data blobs without tripping
        // FAIL_ON_NULL_FOR_PRIMITIVES; the compact constructor unboxes to false.
        inventoryCancellationAcked = inventoryCancellationAcked != null && inventoryCancellationAcked;
        // paymentTerms stays null on legacy rows; consumers fall back to the
        // on-shipment path.
        outstandingReplenishmentLineIds = outstandingReplenishmentLineIds == null
            ? Set.of()
            : outstandingReplenishmentLineIds;
        // true once any non-pegged (shortage top-up) replenishment has
        // been fulfilled, meaning the saga must retry reservation rather than
        // ship straight off the order-pegged peg. Legacy/missing → false.
        sawNonPeggedReplenishment = sawNonPeggedReplenishment != null && sawNonPeggedReplenishment;
        // requestedDeliveryDate stays null on legacy rows / dateless orders;
        // the worker treats null as "no fence gating" (reserve immediately).
        // Completion-gate flags; legacy/missing → false.
        orderShipped = orderShipped != null && orderShipped;
        orderSettled = orderSettled != null && orderSettled;
    }

    public static FulfilmentSagaData none() {
        return new FulfilmentSagaData(false, null, Set.of(), false, null, false, false);
    }

    /** Stamp the order's commercial payment terms at saga creation. */
    public FulfilmentSagaData withPaymentTerms(String paymentTerms) {
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled
        );
    }

    /** Stamp the order's need-by date (ISO {@code yyyy-MM-dd}) at saga creation. */
    public FulfilmentSagaData withRequestedDeliveryDate(String requestedDeliveryDate) {
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled
        );
    }

    /** Record inventory's compensation ack ({@code inventory.SalesOrderCancellationApplied}). */
    public FulfilmentSagaData withInventoryCancellationAcked() {
        return new FulfilmentSagaData(
            true,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled
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
            paymentTerms,
            lineIds == null ? Set.of() : new LinkedHashSet<>(lineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled
        );
    }

    /**
     * Remove a single line id from the outstanding-replenishment set when its
     * {@code ReplenishmentFulfilled} arrives. Idempotent on a line id that's
     * already absent (redelivery). {@code pegged} records whether this
     * was an order-pegged completion (already reserved → ship without retry) or
     * a shortage top-up (pool restocked → must retry reservation); a single
     * non-pegged fulfilment latches {@link #sawNonPeggedReplenishment()}.
     */
    public FulfilmentSagaData withReplenishmentLineFulfilled(UUID salesOrderLineId, boolean pegged) {
        if (!outstandingReplenishmentLineIds.contains(salesOrderLineId)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(outstandingReplenishmentLineIds);
        next.remove(salesOrderLineId);
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            paymentTerms,
            next,
            sawNonPeggedReplenishment || !pegged,
            requestedDeliveryDate,
            orderShipped,
            orderSettled
        );
    }

    /**
     * Latch the completion-gate {@code orderShipped} flag — the order has been
     * fully shipped ({@code inventory.ShipmentPosted} with
     * {@code orderFullyShipped}).
     */
    public FulfilmentSagaData withOrderShipped() {
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            true,
            orderSettled
        );
    }

    /**
     * Latch the completion-gate {@code orderSettled} flag — every invoice for the
     * order is fully paid ({@code finance.CustomerPaymentReceived} with
     * {@code orderFullySettled}).
     */
    public FulfilmentSagaData withOrderSettled() {
        return new FulfilmentSagaData(
            inventoryCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            true
        );
    }

    /** True when every outstanding replenishment has been fulfilled. */
    public boolean allReplenishmentLinesFulfilled() {
        return outstandingReplenishmentLineIds.isEmpty();
    }

    /**
     * True once at least one fulfilled line was a non-pegged (shortage) top-up,
     * meaning that when the outstanding set empties the saga must retry
     * reservation to claim the restocked pool rather than ship straight off the
     * order-pegged peg. The record's own {@link #sawNonPeggedReplenishment()}
     * accessor (normalised non-null by the compact constructor) is the backing
     * value; this alias reads better at the decision site.
     */
    public boolean requiresReservationRetry() {
        return sawNonPeggedReplenishment;
    }

    /** Inventory has acked the cancel — the saga can advance to {@code compensated}. */
    public boolean cancellationAcked() {
        return Boolean.TRUE.equals(inventoryCancellationAcked);
    }

    /** Completion gate: the order has been fully shipped. */
    public boolean isOrderShipped() {
        return Boolean.TRUE.equals(orderShipped);
    }

    /** Completion gate: every invoice for the order is fully paid. */
    public boolean isOrderSettled() {
        return Boolean.TRUE.equals(orderSettled);
    }

    /** Completion gate: both legs met → the saga may move to {@code completed}. */
    public boolean isReadyToComplete() {
        return isOrderShipped() && isOrderSettled();
    }
}
