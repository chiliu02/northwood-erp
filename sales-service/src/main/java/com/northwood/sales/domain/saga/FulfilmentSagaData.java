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
 *   <li>{@link #outstandingCompensationLegs} / {@link #failedCompensationLegs} —
 *       the <b>multi-leg compensation drain</b> (a third instance of the
 *       set-drain join the outstanding-replenishment set already demonstrates).
 *       On cancel/reject, inventory enumerates the order-pegged supply legs whose
 *       committed PO / released work order must be withdrawn (leg id
 *       {@code "<targetService>:<salesOrderLineId>"} —
 *       {@code "purchasing:<lineId>"} / {@code "manufacturing:<lineId>"});
 *       {@code applyInventoryCancellationApplied} stamps
 *       them as {@code outstandingCompensationLegs} and the saga parks in
 *       {@code compensating}. Each arriving {@code PurchaseOrderCancellationApplied}
 *       / {@code WorkOrderCancellationApplied} drains its leg via
 *       {@link #withCompensationLegAcked(String, boolean)} — a failure ack (an
 *       un-compensatable leaf: a PO already received, a WO already consuming
 *       material) also records the leg in {@code failedCompensationLegs}. When the
 *       outstanding set empties the saga branches: no failures → {@code compensated};
 *       any failure → {@code compensation_failed}. The reservation undo is <em>not</em>
 *       a tracked leg — inventory's cancellation ack is itself the proof it
 *       released; zero PO/WO legs means the saga goes straight to {@code compensated}
 *       (the common path).</li>
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
 * field existed (or carrying just {@code {}}) deserialise cleanly. A legacy blob
 * still carrying the retired {@code inventoryCancellationAcked} boolean latch (now
 * generalised into the compensation-leg sets) is tolerated by Jackson 3's
 * default-disabled {@code FAIL_ON_UNKNOWN_PROPERTIES} — the unknown field is
 * ignored, keeping this domain record free of serde annotations.
 */
public record FulfilmentSagaData(
    String paymentTerms,
    Set<UUID> outstandingReplenishmentLineIds,
    Boolean sawNonPeggedReplenishment,
    String requestedDeliveryDate,
    Boolean orderShipped,
    Boolean orderSettled,
    Set<String> outstandingCompensationLegs,
    Set<String> failedCompensationLegs
) {

    public FulfilmentSagaData {
        // paymentTerms stays null on legacy rows; consumers fall back to the
        // on-shipment path.
        outstandingReplenishmentLineIds = outstandingReplenishmentLineIds == null
            ? Set.of()
            : outstandingReplenishmentLineIds;
        // Boxed Boolean (not boolean) so Jackson maps missing JSON fields to
        // null on legacy saga.data blobs without tripping
        // FAIL_ON_NULL_FOR_PRIMITIVES; the compact constructor unboxes to false.
        // true once any non-pegged (shortage top-up) replenishment has
        // been fulfilled, meaning the saga must retry reservation rather than
        // ship straight off the order-pegged peg. Legacy/missing → false.
        sawNonPeggedReplenishment = sawNonPeggedReplenishment != null && sawNonPeggedReplenishment;
        // requestedDeliveryDate stays null on legacy rows / dateless orders;
        // the worker treats null as "no fence gating" (reserve immediately).
        // Completion-gate flags; legacy/missing → false.
        orderShipped = orderShipped != null && orderShipped;
        orderSettled = orderSettled != null && orderSettled;
        // Compensation-drain sets; legacy/missing → empty (no compensation in flight).
        outstandingCompensationLegs = outstandingCompensationLegs == null
            ? Set.of()
            : outstandingCompensationLegs;
        failedCompensationLegs = failedCompensationLegs == null
            ? Set.of()
            : failedCompensationLegs;
    }

    public static FulfilmentSagaData none() {
        return new FulfilmentSagaData(null, Set.of(), false, null, false, false, Set.of(), Set.of());
    }

    /** Stamp the order's commercial payment terms at saga creation. */
    public FulfilmentSagaData withPaymentTerms(String paymentTerms) {
        return new FulfilmentSagaData(
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled,
            new LinkedHashSet<>(outstandingCompensationLegs),
            new LinkedHashSet<>(failedCompensationLegs)
        );
    }

    /** Stamp the order's need-by date (ISO {@code yyyy-MM-dd}) at saga creation. */
    public FulfilmentSagaData withRequestedDeliveryDate(String requestedDeliveryDate) {
        return new FulfilmentSagaData(
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled,
            new LinkedHashSet<>(outstandingCompensationLegs),
            new LinkedHashSet<>(failedCompensationLegs)
        );
    }

    /**
     * Stamp the set of sales-order-line ids the saga is awaiting an
     * {@code inventory.ReplenishmentFulfilled} for. Called by
     * {@code applyStockReserved} when reservation comes back partial/failed.
     */
    public FulfilmentSagaData withOutstandingReplenishmentLineIds(Set<UUID> lineIds) {
        return new FulfilmentSagaData(
            paymentTerms,
            lineIds == null ? Set.of() : new LinkedHashSet<>(lineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled,
            new LinkedHashSet<>(outstandingCompensationLegs),
            new LinkedHashSet<>(failedCompensationLegs)
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
            paymentTerms,
            next,
            sawNonPeggedReplenishment || !pegged,
            requestedDeliveryDate,
            orderShipped,
            orderSettled,
            new LinkedHashSet<>(outstandingCompensationLegs),
            new LinkedHashSet<>(failedCompensationLegs)
        );
    }

    /**
     * Latch the completion-gate {@code orderShipped} flag — the order has been
     * fully shipped ({@code inventory.ShipmentPosted} with
     * {@code orderFullyShipped}).
     */
    public FulfilmentSagaData withOrderShipped() {
        return new FulfilmentSagaData(
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            true,
            orderSettled,
            new LinkedHashSet<>(outstandingCompensationLegs),
            new LinkedHashSet<>(failedCompensationLegs)
        );
    }

    /**
     * Latch the completion-gate {@code orderSettled} flag — every invoice for the
     * order is fully paid ({@code finance.CustomerPaymentReceived} with
     * {@code orderFullySettled}).
     */
    public FulfilmentSagaData withOrderSettled() {
        return new FulfilmentSagaData(
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            true,
            new LinkedHashSet<>(outstandingCompensationLegs),
            new LinkedHashSet<>(failedCompensationLegs)
        );
    }

    /**
     * Stamp the outstanding compensation-leg set when a cancel/reject lands with
     * committed order-pegged supply to withdraw ({@code "PO:<lineId>"} /
     * {@code "WO:<lineId>"}). Replaces today's single-ack latch: an empty set
     * means nothing to compensate (the saga goes straight to {@code compensated});
     * a non-empty set parks the saga in {@code compensating} until every leg acks.
     */
    public FulfilmentSagaData withOutstandingCompensationLegs(Set<String> legIds) {
        return new FulfilmentSagaData(
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled,
            legIds == null ? Set.of() : new LinkedHashSet<>(legIds),
            new LinkedHashSet<>(failedCompensationLegs)
        );
    }

    /**
     * Drain a single compensation leg when its ack arrives. Idempotent on a leg
     * id already absent (redelivery) — the same guard
     * {@link #withReplenishmentLineFulfilled} uses. A {@code failed} ack (the
     * downstream service refused: an un-compensatable leaf) also records the leg
     * in {@link #failedCompensationLegs} so the terminal branch can escalate to
     * {@code compensation_failed} rather than silently completing.
     */
    public FulfilmentSagaData withCompensationLegAcked(String legId, boolean failed) {
        if (!outstandingCompensationLegs.contains(legId)) {
            return this;
        }
        Set<String> nextOutstanding = new LinkedHashSet<>(outstandingCompensationLegs);
        nextOutstanding.remove(legId);
        Set<String> nextFailed = new LinkedHashSet<>(failedCompensationLegs);
        if (failed) {
            nextFailed.add(legId);
        }
        return new FulfilmentSagaData(
            paymentTerms,
            new LinkedHashSet<>(outstandingReplenishmentLineIds),
            sawNonPeggedReplenishment,
            requestedDeliveryDate,
            orderShipped,
            orderSettled,
            nextOutstanding,
            nextFailed
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

    /** True when every outstanding compensation leg has been acked. */
    public boolean allCompensationLegsAcked() {
        return outstandingCompensationLegs.isEmpty();
    }

    /**
     * True when at least one compensation leg failed (an un-compensatable leaf) —
     * the saga must reach {@code compensation_failed}, not {@code compensated},
     * once the outstanding set empties.
     */
    public boolean hasCompensationFailures() {
        return !failedCompensationLegs.isEmpty();
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
