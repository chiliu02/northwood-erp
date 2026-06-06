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
     * amendment on the saga's position in the flow (only before stock is
     * reserved, this slice).
     */
    Optional<String> currentState(UUID salesOrderHeaderId);

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
     * per-line reply to a line amendment (§1G). Reconciles the saga's
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
     * Apply {@code inventory.SalesOrderCancellationApplied} — the sole
     * compensation ack (the manufacturing leg was retired). Records the
     * inventory ack on saga data and, when in {@code compensating}, transitions
     * {@code compensating → compensated} and returns {@code "compensated"} so the
     * caller can emit {@code sales.SalesOrderCompensated}.
     */
    String applyInventoryCancellationApplied(UUID salesOrderHeaderId);

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
