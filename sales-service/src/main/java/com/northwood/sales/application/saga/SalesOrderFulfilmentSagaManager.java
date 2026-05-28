package com.northwood.sales.application.saga;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Saga state-machine port for the sales-order fulfilment flow. Holds saga
 * state truth — every transition the saga can take is a method here. The
 * implementation depends only on {@code SalesOrderFulfilmentSagaPort} (saga
 * row CRUD) and an {@code ObjectMapper} for {@code saga.data} JSON. All side
 * effects (event emission, projection writes, calls into other services /
 * aggregates) are the caller's job — typically the {@code *SagaWorker} shell
 * for worker-driven advances and the inbox handler shells for inbox-driven
 * advances.
 *
 * <p>Each {@code applyXxx} returns the saga's new state (or its current state
 * if the transition was a no-op). Callers use the return value to gate
 * post-saga side effects: a {@code "compensated"} return triggers
 * {@code SalesOrderCompensated} emission; a {@code "rejected"}
 * return triggers a {@code rejected} status projection; etc.
 *
 * <p>Apply methods take saga-relevant primitives (UUID + small fields), not
 * inbox payload types — the inbox handler is the only place that knows about
 * the wire format.
 */
public interface SalesOrderFulfilmentSagaManager {

    // ------------------------------------------------------------
    // Lifecycle (called from SalesOrderService)
    // ------------------------------------------------------------

    /** Insert a fresh saga at {@code started}. */
    void insertStarted(UUID salesOrderHeaderId, String dataJson);

    /**
     * Flip {@code → compensating} in response to a cancel command. Throws
     * {@link SagaNotFoundException} if no saga exists for the given order.
     */
    void requestCompensation(UUID salesOrderHeaderId);

    // ------------------------------------------------------------
    // Worker drain (called from SalesOrderFulfilmentSagaWorker)
    // ------------------------------------------------------------

    /**
     * Drain due sagas via the abstract {@code SagaManager<S>} base. The
     * worker shell supplies its own {@code workerId} and an {@code advanceFn}
     * that mutates the saga + emits any saga-driven outbound events. The
     * manager itself does not know how to advance worker-driven states; it
     * only provides per-saga transactional plumbing.
     */
    void drain(int batchSize, String workerId, Consumer<SalesOrderFulfilmentSaga> advanceFn);

    // ------------------------------------------------------------
    // Inbox-driven transitions
    //
    // Each method finds the saga, decides whether to transition, persists,
    // and returns the saga's new state. Callers gate side effects on the
    // returned state. Returns the saga's current state (unchanged) if the
    // transition was a no-op (e.g. wrong source state for the event).
    // ------------------------------------------------------------

    /**
     * Apply {@code inventory.StockReserved}. Routing depends on
     * {@code reservationStatus}:
     * <ul>
     *   <li>{@code reserved} (full cover) — transitions
     *       {@code stock_reservation_requested → ready_to_ship}, skipping
     *       manufacturing entirely. Every line is already reserved against
     *       {@code stock_balance}, so there's nothing to manufacture.</li>
     *   <li>{@code partially_reserved} / {@code failed} —
     *       {@code shortageByLineNumber} must be non-null and non-empty
     *       (throws {@link IllegalStateException} otherwise — inventory
     *       has nothing meaningful to say about a non-success outcome
     *       without per-line shortages). Stashes the map onto
     *       {@code saga.data}, transitions {@code → stock_reservation_incomplete}, and
     *       parks the saga for immediate worker pickup so the worker
     *       forwards the shortage as a {@code ManufacturingRequested}
     *       on the next tick.</li>
     * </ul>
     */
    String applyStockReserved(
        UUID salesOrderHeaderId,
        String reservationStatus,
        Map<Integer, BigDecimal> shortageByLineNumber
    );

    /**
     * Apply {@code manufacturing.WorkOrderCreated}. Records the WO id in the
     * saga's outstanding set (top-level WOs only — sub-assembly child WOs
     * with non-null {@code parentWorkOrderId} are no-ops). On the first
     * top-level WO, transitions {@code manufacturing_requested →
     * manufacturing_in_progress}.
     */
    String applyWorkOrderCreated(UUID salesOrderHeaderId, UUID workOrderId, UUID parentWorkOrderId);

    /**
     * Apply {@code manufacturing.WorkOrderManufacturingCompleted}. Moves the
     * WO id from outstanding to completed. Transitions
     * {@code → ready_to_ship} once every WO sales has heard about has finished
     * (cross-partition-safe gate via expectedWorkOrderCount when known).
     * Sub-assembly child WOs are no-ops.
     */
    String applyWorkOrderManufacturingCompleted(UUID salesOrderHeaderId, UUID workOrderId, UUID parentWorkOrderId);

    /**
     * Apply {@code manufacturing.ManufacturingDispatched}. On all-rejected
     * (zero accepted lines) and saga still in {@code manufacturing_requested},
     * transitions {@code → rejected}. Otherwise stamps
     * {@code expectedWorkOrderCount} on saga data so the cross-partition
     * completion gate is monotonic.
     */
    String applyManufacturingDispatched(UUID salesOrderHeaderId, int acceptedCount, int totalLines);

    /**
     * §2.36: reroute an all-rejected-as-purchased-only {@code ManufacturingDispatched}
     * to the new {@code purchasing_requested} branch instead of terminal
     * {@code rejected}. Only valid when the saga is in
     * {@code manufacturing_requested}; no-op otherwise. Returns the per-line
     * shortage map (read from {@code saga.data} where {@link #applyStockReserved}
     * stashed it) so the caller can build a
     * {@code sales.SalesOrderPurchasingRequested} event with the right
     * quantities. Returns {@code Optional.empty()} if the transition was
     * declined — caller falls back to the existing rejection path.
     *
     * <p>Stashes the {@code outstandingPurchasingLineIds} set on saga.data
     * (from {@code outstandingLineIds}) so the §2.36 fan-in handler knows
     * which {@code ReplenishmentFulfilled} events are addressed to this saga.
     *
     * <p>Used by {@code ManufacturingDispatchedHandler} when every rejected
     * line carries outcome {@code rejected_not_manufactured} (i.e., the
     * SKU has no active BOM by design — purchased-only). Mixed cases (some
     * accepted, some {@code rejected_not_manufactured}, or any
     * {@code rejected_no_bom}) take the existing §4.2-closure full-rejection
     * path — restoring symmetry for those is tracked as a §2.36 follow-up.
     */
    java.util.Optional<PurchasingDivergence> applyManufacturingDispatchedReroutingToPurchasing(
        UUID salesOrderHeaderId,
        java.util.Set<UUID> outstandingLineIds
    );

    /** §2.36 result for {@link #applyManufacturingDispatchedReroutingToPurchasing}. */
    record PurchasingDivergence(
        Map<Integer, java.math.BigDecimal> shortageByLineNumber
    ) {}

    /**
     * §2.36 Slice E: apply {@code inventory.ReplenishmentFulfilled} addressed
     * to a specific sales-order line. Removes the line id from the saga's
     * {@code outstandingPurchasingLineIds} set. When the set empties and the
     * saga is still in {@code purchasing_requested}, transitions back to
     * {@code stock_reservation_requested} so the worker re-tries reservation
     * against the now-restocked inventory.
     *
     * <p>Returns the saga's new state (or current state for a no-op). Callers
     * gate on {@code "stock_reservation_requested"} to know that re-reservation
     * is required — typically the handler emits no follow-on event and lets
     * the existing worker tick pick the saga back up.
     *
     * <p>Idempotent against duplicate fulfilment events (line already absent
     * from the set) and against late deliveries (saga no longer in
     * {@code purchasing_requested} — e.g. operator cancelled, or the saga
     * already advanced via some other path).
     */
    String applyReplenishmentFulfilled(UUID salesOrderHeaderId, UUID salesOrderLineId);

    /** Apply {@code inventory.ShipmentPosted}. Transitions {@code ready_to_ship → goods_shipped}. */
    String applyShipmentPosted(UUID salesOrderHeaderId);

    /** Apply {@code finance.CustomerInvoiceCreated}. Transitions {@code goods_shipped → invoice_created}. */
    String applyCustomerInvoiceCreated(UUID salesOrderHeaderId);

    /**
     * Apply {@code finance.CustomerPaymentReceived}. On full settlement,
     * transitions to {@code completed}. On partial, transitions to
     * {@code invoice_partially_paid} and parks for further payments.
     */
    String applyCustomerPaymentReceived(UUID salesOrderHeaderId, boolean fullySettled);

    /**
     * Apply {@code inventory.SalesOrderCancellationApplied}. Records the
     * inventory ack on saga data. When manufacturing's ack has also arrived,
     * transitions {@code compensating → compensated} and returns
     * {@code "compensated"} so the caller can emit
     * {@code sales.SalesOrderCompensated}.
     */
    String applyInventoryCancellationApplied(UUID salesOrderHeaderId);

    /** Mirror of {@link #applyInventoryCancellationApplied} for manufacturing's ack. */
    String applyManufacturingCancellationApplied(UUID salesOrderHeaderId);

    // ------------------------------------------------------------
    // Exceptions
    // ------------------------------------------------------------

    /** Thrown by {@link #requestCompensation} when no saga exists for the given order. */
    class SagaNotFoundException extends RuntimeException {
        public SagaNotFoundException(UUID salesOrderHeaderId) {
            super("No fulfilment saga for sales_order_header_id=" + salesOrderHeaderId);
        }
    }
}
