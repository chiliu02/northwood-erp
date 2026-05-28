package com.northwood.sales.domain.saga;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shape of the JSON blob stored in {@code sales.sales_order_fulfilment_saga.data}.
 *
 * <ul>
 *   <li>{@link #shortageByLineNumber} — non-empty only when stock came back as a
 *       partial reservation. The saga worker reads this to decide how much of
 *       each line to forward to manufacturing.</li>
 *   <li>{@link #expectedWorkOrderCount} — total number of top-level work orders
 *       this sales order will produce, learned from
 *       {@code manufacturing.ManufacturingDispatched} (counts the
 *       {@code accepted} line outcomes). Stamped once when the dispatch event
 *       arrives. {@code null} for sagas created before this field existed —
 *       the legacy outstanding-set logic still drives those.</li>
 *   <li>{@link #outstandingWorkOrderIds} — work orders manufacturing has
 *       reported as created but not yet completed for this sales order. Grows
 *       as {@code WorkOrderCreated} arrives, shrinks as {@code WorkOrderManufacturingCompleted}
 *       arrives. Pre-{@link #expectedWorkOrderCount} sagas advance to
 *       {@code ready_to_ship} when this set is empty AND
 *       {@link #completedWorkOrderIds} is non-empty.</li>
 *   <li>{@link #completedWorkOrderIds} — work orders that have completed.</li>
 * </ul>
 *
 * <p><b>Cross-partition race fix (2026-05-05):</b> when
 * {@code expectedWorkOrderCount} is known, the advance gate becomes
 * {@code completed.size() >= expectedWorkOrderCount}. This eliminates the
 * race where a sibling WO's {@code Created} arrives after another sibling's
 * {@code Completed} (briefly leaving outstanding empty and prematurely
 * advancing the saga). The dispatched-count is set at request-receipt time
 * and is monotonic, so a "completion arriving before a sibling's creation"
 * can no longer trip the gate.
 *
 * <p>The compact constructor defaults null fields to empty collections so
 * sagas written before fields were added (carrying just
 * {@code {"shortageByLineNumber":...}} or {@code {}}) deserialise cleanly.
 */
public record FulfilmentSagaData(
    Map<Integer, BigDecimal> shortageByLineNumber,
    Integer expectedWorkOrderCount,
    Set<UUID> outstandingWorkOrderIds,
    Set<UUID> completedWorkOrderIds,
    Boolean inventoryCancellationAcked,
    Boolean manufacturingCancellationAcked,
    /**
     * §2.31 Slice B: the order's snapshotted commercial payment terms,
     * stashed at saga creation by {@code SalesOrderService.placeOrder} so the
     * worker can branch at {@code started} ({@code on_shipment} → existing
     * stock-reservation request; {@code prepayment} → emit
     * {@code PrepaymentInvoiceRequested}) and so
     * {@code applyCustomerPaymentReceived} can route full settlement to
     * {@code completed} (on-shipment) or {@code prepaid} (prepayment).
     * One of {@code "on_shipment"} / {@code "prepayment"}; null on saga rows
     * written before this field existed (legacy fallback: treat as
     * {@code on_shipment} — the only flow that existed pre-§2.31).
     */
    String paymentTerms,
    /**
     * §2.36 Slice E: outstanding sales-order-line back-references for
     * purchased-only short lines that are awaiting replenishment fulfilment.
     * Populated when the saga reroutes to {@code purchasing_requested}; each
     * arriving {@code inventory.ReplenishmentFulfilled} with a matching
     * {@code sourceSalesOrderLineId} removes its entry. When the set empties,
     * the worker advances the saga back into {@code stock_reservation_requested}
     * to retry reservation against the now-restocked inventory.
     * Null on legacy saga rows; the compact constructor maps that to an empty set.
     */
    Set<UUID> outstandingPurchasingLineIds
) {

    public FulfilmentSagaData {
        shortageByLineNumber = shortageByLineNumber == null ? Map.of() : shortageByLineNumber;
        outstandingWorkOrderIds = outstandingWorkOrderIds == null ? Set.of() : outstandingWorkOrderIds;
        completedWorkOrderIds = completedWorkOrderIds == null ? Set.of() : completedWorkOrderIds;
        // expectedWorkOrderCount stays null when unset — null is the sentinel
        // for "use legacy outstanding-set logic" so existing sagas still work.
        // The two ack fields are boxed Boolean (not boolean) so Jackson can
        // map missing JSON fields to null on legacy saga.data blobs without
        // tripping FAIL_ON_NULL_FOR_PRIMITIVES; the compact constructor
        // unboxes to false.
        inventoryCancellationAcked = inventoryCancellationAcked != null && inventoryCancellationAcked;
        manufacturingCancellationAcked = manufacturingCancellationAcked != null && manufacturingCancellationAcked;
        // paymentTerms stays null on legacy rows; consumers fall back to the
        // on-shipment path (the only path that existed pre-§2.31 Slice B).
        outstandingPurchasingLineIds = outstandingPurchasingLineIds == null
            ? Set.of()
            : outstandingPurchasingLineIds;
    }

    public static FulfilmentSagaData none() {
        return new FulfilmentSagaData(Map.of(), null, Set.of(), Set.of(), false, false, null, Set.of());
    }

    /** Stamp the order's commercial payment terms at saga creation. */
    public FulfilmentSagaData withPaymentTerms(String paymentTerms) {
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            expectedWorkOrderCount,
            new LinkedHashSet<>(outstandingWorkOrderIds),
            new LinkedHashSet<>(completedWorkOrderIds),
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingPurchasingLineIds)
        );
    }

    public boolean hasShortage() {
        return !shortageByLineNumber.isEmpty();
    }

    /** Stamp the expected work-order count from {@code ManufacturingDispatched}. */
    public FulfilmentSagaData withExpectedWorkOrderCount(int count) {
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            count,
            new LinkedHashSet<>(outstandingWorkOrderIds),
            new LinkedHashSet<>(completedWorkOrderIds),
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingPurchasingLineIds)
        );
    }

    /** Mark a work order as outstanding (created but not yet completed). */
    public FulfilmentSagaData withWorkOrderCreated(UUID workOrderId) {
        if (outstandingWorkOrderIds.contains(workOrderId) || completedWorkOrderIds.contains(workOrderId)) {
            return this;  // idempotent
        }
        Set<UUID> outstanding = new LinkedHashSet<>(outstandingWorkOrderIds);
        outstanding.add(workOrderId);
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            expectedWorkOrderCount,
            outstanding,
            new LinkedHashSet<>(completedWorkOrderIds),
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingPurchasingLineIds)
        );
    }

    /** Move a work order from outstanding to completed. */
    public FulfilmentSagaData withWorkOrderCompleted(UUID workOrderId) {
        if (completedWorkOrderIds.contains(workOrderId)) {
            return this;  // idempotent
        }
        Set<UUID> outstanding = new LinkedHashSet<>(outstandingWorkOrderIds);
        outstanding.remove(workOrderId);
        Set<UUID> completed = new LinkedHashSet<>(completedWorkOrderIds);
        completed.add(workOrderId);
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            expectedWorkOrderCount,
            outstanding,
            completed,
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingPurchasingLineIds)
        );
    }

    /** Record inventory's compensation ack ({@code inventory.SalesOrderCancellationApplied}). */
    public FulfilmentSagaData withInventoryCancellationAcked() {
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            expectedWorkOrderCount,
            new LinkedHashSet<>(outstandingWorkOrderIds),
            new LinkedHashSet<>(completedWorkOrderIds),
            true,
            manufacturingCancellationAcked,
            paymentTerms,
            new LinkedHashSet<>(outstandingPurchasingLineIds)
        );
    }

    /** Record manufacturing's compensation ack ({@code manufacturing.SalesOrderCancellationApplied}). */
    public FulfilmentSagaData withManufacturingCancellationAcked() {
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            expectedWorkOrderCount,
            new LinkedHashSet<>(outstandingWorkOrderIds),
            new LinkedHashSet<>(completedWorkOrderIds),
            inventoryCancellationAcked,
            true,
            paymentTerms,
            new LinkedHashSet<>(outstandingPurchasingLineIds)
        );
    }

    /**
     * §2.36 Slice E: stamp the set of sales-order-line ids that the saga is
     * waiting on a {@code ReplenishmentFulfilled} for. Called by
     * {@link com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager#applyManufacturingDispatchedReroutingToPurchasing}
     * at reroute time so the fan-in handler knows which line-ids are
     * "addressed to us".
     */
    public FulfilmentSagaData withOutstandingPurchasingLineIds(Set<UUID> lineIds) {
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            expectedWorkOrderCount,
            new LinkedHashSet<>(outstandingWorkOrderIds),
            new LinkedHashSet<>(completedWorkOrderIds),
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            lineIds == null ? Set.of() : new LinkedHashSet<>(lineIds)
        );
    }

    /**
     * §2.36 Slice E: remove a single line id from the outstanding-purchasing
     * set when its {@code ReplenishmentFulfilled} arrives. Idempotent on a
     * line id that's already absent (redelivery).
     */
    public FulfilmentSagaData withPurchasingLineFulfilled(UUID salesOrderLineId) {
        if (!outstandingPurchasingLineIds.contains(salesOrderLineId)) {
            return this;
        }
        Set<UUID> next = new LinkedHashSet<>(outstandingPurchasingLineIds);
        next.remove(salesOrderLineId);
        return new FulfilmentSagaData(
            new LinkedHashMap<>(shortageByLineNumber),
            expectedWorkOrderCount,
            new LinkedHashSet<>(outstandingWorkOrderIds),
            new LinkedHashSet<>(completedWorkOrderIds),
            inventoryCancellationAcked,
            manufacturingCancellationAcked,
            paymentTerms,
            next
        );
    }

    /** §2.36 Slice E: true when every purchasing-line replenishment has arrived. */
    public boolean allPurchasingLinesFulfilled() {
        return outstandingPurchasingLineIds.isEmpty();
    }

    /** Both downstream services have acked the cancel — saga can advance to {@code compensated}. */
    public boolean bothCancellationAcksReceived() {
        return Boolean.TRUE.equals(inventoryCancellationAcked)
            && Boolean.TRUE.equals(manufacturingCancellationAcked);
    }

    /**
     * True when every WO sales has heard about has completed. With
     * {@link #expectedWorkOrderCount} stamped, this is the cross-partition-safe
     * variant: completed.size() &gt;= expectedCount. Without it, fall back to
     * the legacy "outstanding is empty AND completed is non-empty" gate.
     */
    public boolean allWorkOrdersComplete() {
        if (expectedWorkOrderCount != null) {
            return completedWorkOrderIds.size() >= expectedWorkOrderCount;
        }
        return outstandingWorkOrderIds.isEmpty() && !completedWorkOrderIds.isEmpty();
    }
}
