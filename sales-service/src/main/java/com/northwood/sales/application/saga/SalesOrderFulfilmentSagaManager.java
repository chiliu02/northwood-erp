package com.northwood.sales.application.saga;

import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import java.util.Optional;
import java.util.Set;
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
     * Current fulfilment-saga state for an order, or empty if no saga row
     * exists. Read-only — used by the application service to gate line
     * amendment on the saga's position in the flow.
     */
    Optional<String> currentState(UUID salesOrderHeaderId);

    /**
     * The order's snapshotted commercial payment terms (wire-format
     * {@code on_shipment} / {@code prepayment} / {@code deposit} …), or empty if
     * no saga row exists / none was stamped. Read-only — used by the application
     * service to apply the finance guard: prepayment/deposit orders raise
     * their invoice <em>before</em> reservation, so they are not amendable past
     * {@code started} even though on-shipment orders are.
     */
    Optional<String> currentPaymentTerms(UUID salesOrderHeaderId);

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
     *       {@code stock_reservation_requested → ready_to_ship}. Every line is
     *       already reserved against {@code stock_balance}, so there's nothing
     *       to replenish.</li>
     *   <li>{@code partially_reserved} / {@code failed} —
     *       {@code shortageLineIds} must be non-empty (throws
     *       {@link IllegalStateException} otherwise — inventory has nothing
     *       meaningful to say about a non-success outcome without per-line
     *       shortages). Inventory has already raised the
     *       {@code ReplenishmentRequest} for each short line in the same
     *       transaction, so sales just records the outstanding line ids on
     *       {@code saga.data} and parks at {@code stock_reservation_incomplete}
     *       until the replenishments fulfil (or one is cancelled).</li>
     * </ul>
     */
    String applyStockReserved(
        UUID salesOrderHeaderId,
        String reservationStatus,
        Set<UUID> shortageLineIds
    );

    /**
     * Apply {@code inventory.ReplenishmentFulfilled} addressed
     * to a specific sales-order line. Removes the line id from the saga's
     * {@code outstandingReplenishmentLineIds} set. When the set empties:
     * <ul>
     *   <li>if every fulfilment was order-pegged ({@code pegged=true}) the
     *       output was already reserved on completion, so the saga goes straight
     *       to {@code ready_to_ship} — no re-reservation;</li>
     *   <li>otherwise (any shortage top-up) it transitions back to
     *       {@code stock_reservation_requested} so the worker re-tries
     *       reservation against the now-restocked pool.</li>
     * </ul>
     *
     * <p>Returns the saga's new state (or current state for a no-op). Callers
     * gate on {@code "stock_reservation_requested"} to know that re-reservation
     * is required — the handler re-emits {@code StockReservationRequested};
     * {@code "ready_to_ship"} needs no follow-up.
     *
     * <p>Idempotent against duplicate fulfilment events (line already absent
     * from the set) and against late deliveries (saga no longer in
     * {@code stock_reservation_incomplete}).
     */
    String applyReplenishmentFulfilled(UUID salesOrderHeaderId, UUID salesOrderLineId, boolean pegged);

    /**
     * Apply {@code inventory.ReplenishmentCancelled} addressed
     * to a specific sales-order line — the replenishment couldn't be sourced
     * (unsourceable SKU, no active BOM, no approved vendor). A short line that
     * can never be replenished means the order can't be fulfilled, so the saga
     * transitions {@code stock_reservation_incomplete → rejected} (any one
     * cancelled line rejects the whole order).
     * Returns {@code "rejected"} so the handler can flip the order header +
     * request compensation. No-op (returns current state) if the saga has
     * already left {@code stock_reservation_incomplete}.
     */
    String applyReplenishmentCancelled(UUID salesOrderHeaderId, UUID salesOrderLineId, String reason);

    /**
     * Apply {@code inventory.SalesOrderLineReservationChanged} — inventory's
     * per-line reply to a line amendment. Reconciles the saga's
     * outstanding-replenishment set:
     * <ul>
     *   <li>{@code lineIsShort} — the amended line couldn't be fully reserved
     *       (inventory raised a replenishment); add it to the outstanding set and
     *       park at {@code stock_reservation_incomplete};</li>
     *   <li>otherwise (fully reserved, or released on removal) — drop it from the
     *       set; if the set empties while parked at
     *       {@code stock_reservation_incomplete}, un-park to {@code ready_to_ship}.</li>
     * </ul>
     * No-op (returns current state) outside the reservation phase
     * ({@code stock_reservation_requested} / {@code ready_to_ship} /
     * {@code stock_reservation_incomplete}).
     */
    String applyLineReservationChanged(UUID salesOrderHeaderId, UUID salesOrderLineId, boolean lineIsShort);

    /**
     * Apply {@code inventory.ShipmentPosted}. The saga's post-supply act is the
     * <b>completion gate</b>, not a status mirror — the header ship status is
     * owned by the line fold ({@code SalesOrder.recordShipped}). So:
     * <ul>
     *   <li>partial shipment ({@code orderFullyShipped=false}) — no-op; the line
     *       fold carries the {@code partially_shipped} header.</li>
     *   <li>completing shipment ({@code orderFullyShipped=true}) — latch the
     *       {@code orderShipped} gate leg and {@code → completed} if the pay leg
     *       ({@code orderSettled}) has also landed, else hold at
     *       {@code supply_secured}.</li>
     * </ul>
     * No-op from any non-{@code supply_secured} state. Uniform across payment
     * terms — prepayment / COD complete here too, once their (earlier or
     * concurrent) payment has latched {@code orderSettled}.
     */
    String applyShipmentPosted(UUID salesOrderHeaderId, boolean orderFullyShipped);

    /**
     * Apply {@code finance.CustomerPaymentReceived}. Two roles, by saga state:
     * <ul>
     *   <li><b>Up-front gate</b> ({@code awaiting_prepayment}): on
     *       {@code invoiceFullySettled} (the up-front prepayment/deposit invoice
     *       is paid) → {@code prepaid} (the worker then requests reservation; the
     *       handler emits {@code SalesOrderPrepaymentSettled}). {@code orderFullySettled}
     *       is latched onto the completion gate — true for a full prepayment,
     *       false for a deposit whose balance is still due.</li>
     *   <li><b>Completion gate</b> ({@code supply_secured}): on
     *       {@code orderFullySettled} (every invoice for the order paid — for a
     *       deposit that means deposit AND balance) latch the {@code orderSettled}
     *       leg and {@code → completed} if the order has also shipped. A
     *       non-order-settling payment (one of several invoices) is a no-op.</li>
     * </ul>
     * No-op from any other state.
     */
    String applyCustomerPaymentReceived(UUID salesOrderHeaderId, boolean invoiceFullySettled, boolean orderFullySettled);

    /**
     * Apply {@code inventory.SalesOrderCancellationApplied} — inventory's
     * reservation-release ack and the confirmation half of the two-phase cancel.
     * {@code outstandingCompensationLegIds} enumerates the order-pegged supply
     * legs (formed by the handler as {@code <targetService>:<salesOrderLineId>})
     * whose committed PO / released work order must still be withdrawn:
     * <ul>
     *   <li><b>Empty</b> (the common case — nothing pegged, or the peg already
     *       fulfilled) — the reservation release is the whole undo, so a
     *       non-terminal saga transitions directly to {@code compensated},
     *       returning {@code "compensated"} so the caller emits
     *       {@code sales.SalesOrderCompensated}.</li>
     *   <li><b>Non-empty</b> — stamp the legs on saga data and park the saga in
     *       {@code compensating} (returns {@code "compensating"}; no terminal event
     *       yet). Each leg's later {@code *CancellationApplied} ack drains via
     *       {@link #applyCompensationAck}.</li>
     * </ul>
     * A no-op on a terminal saga (the {@code rejected}/unsourceable path also
     * releases via this ack).
     */
    String applyInventoryCancellationApplied(UUID salesOrderHeaderId, Set<String> outstandingCompensationLegIds);

    /**
     * Apply a single compensation-leg ack ({@code purchasing.PurchaseOrderCancellationApplied}
     * / {@code manufacturing.WorkOrderCancellationApplied}) — the multi-leg drain.
     * Removes {@code legId} from the saga's {@code outstandingCompensationLegs}
     * ({@code failed=true} also records it as an un-compensatable leaf). When the
     * outstanding set empties the saga branches:
     * <ul>
     *   <li>no failures → {@code compensated} (returns {@code "compensated"} → emit
     *       {@code sales.SalesOrderCompensated});</li>
     *   <li>any failure → {@code compensation_failed} (returns
     *       {@code "compensation_failed"} → emit {@code sales.SalesOrderCompensationFailed}).</li>
     * </ul>
     * Idempotent against duplicate acks (leg already absent) and against acks for a
     * saga no longer in {@code compensating} (late straggler / terminal). Returns
     * the saga's state (unchanged) on a no-op so the caller emits no terminal event.
     */
    String applyCompensationAck(UUID salesOrderHeaderId, String legId, boolean failed);
}
