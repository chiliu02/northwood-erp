package com.northwood.manufacturing.application.saga;

import com.northwood.manufacturing.domain.saga.WorkOrderSaga;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Saga state-machine port for the make-to-order flow. Holds saga state truth
 * — every transition the saga can take is a method here. Implementation
 * depends only on {@code WorkOrderSagaPort} (saga row CRUD) and an
 * {@code ObjectMapper} for {@code saga.data} JSON. All side effects
 * (event emission, projection writes, calls into other services / aggregates)
 * are caller's job — the {@code WorkOrderSagaWorker} shell for worker-driven
 * advances and the inbox handler shells for inbox-driven advances.
 *
 * <p>Each {@code applyXxx} returns the saga's new state (or its current state
 * if the transition was a no-op). Callers gate post-saga side effects on the
 * return value — e.g. {@code "raw_material_shortage"} triggers a
 * {@code RawMaterialShortageDetected} emission on the handler side.
 */
public interface WorkOrderSagaManager {

    // ------------------------------------------------------------
    // Lifecycle (called from WorkOrderReleaseService)
    // ------------------------------------------------------------

    /**
     * Insert a saga at {@code work_order_created} with {@code workOrderId}
     * pre-attached. Called from {@code WorkOrderReleaseService} for every work
     * order it releases — the root stock-replenishment WO and each sub-assembly
     * child during recursion (all make-to-stock, so the sales-order pair is
     * null). The {@code started}-entry path (the sales-driven
     * {@code ManufacturingRequested} flow) was retired; the saga is now always
     * entered here.
     */
    void insertAttachedToWorkOrder(
        UUID salesOrderHeaderId, UUID salesOrderLineId, UUID workOrderId, String dataJson
    );

    // ------------------------------------------------------------
    // Worker drain (called from WorkOrderSagaWorker)
    // ------------------------------------------------------------

    /**
     * Drain due sagas via the abstract {@code SagaManager<S, P>} base. The
     * worker shell supplies its own {@code workerId} and an {@code advanceFn}
     * that mutates the saga + emits any saga-driven outbound events.
     */
    void drain(int batchSize, String workerId, Consumer<WorkOrderSaga> advanceFn);

    // ------------------------------------------------------------
    // Inbox-driven transitions
    // ------------------------------------------------------------

    /**
     * Apply {@code inventory.RawMaterialsReserved}. On {@code "reserved"} status,
     * transitions {@code raw_material_reservation_requested → raw_materials_reserved}.
     * On any other status, stashes {@code shortageByProductId} on saga.data and
     * transitions {@code → raw_material_shortage}. Returns the new state so the
     * caller can decide whether to emit {@code RawMaterialShortageDetected}.
     * Returns the unchanged state when the saga isn't in
     * {@code raw_material_reservation_requested} (defensive — out-of-order event).
     */
    String applyRawMaterialsReserved(
        UUID workOrderId, String status, Map<UUID, BigDecimal> shortageByProductId
    );

    /**
     * Apply {@code inventory.GoodsReceived} for one candidate saga. Decrements
     * the saga's stashed shortage by the receipt quantities; un-parks
     * ({@code raw_material_shortage → raw_material_reservation_requested}) when
     * every entry is covered, otherwise saves the narrowed shortage and stays at
     * {@code raw_material_shortage}.
     *
     * <p>On un-park the saga lands directly at
     * {@code raw_material_reservation_requested} (not {@code work_order_created},
     * which misleadingly read as "a new WO is created"). The caller
     * ({@code GoodsReceivedHandler}) re-emits {@code RawMaterialReservationRequested}
     * via {@link RawMaterialReservationRequestEmitter} when it sees that state —
     * mirroring the sales saga's {@code ReplenishmentFulfilledHandler}, which goes
     * straight to {@code stock_reservation_requested} rather than bouncing through
     * an entry state. Hence the outcome carries the {@code workOrderId} the caller
     * needs to emit for.
     *
     * <p>The {@code state} is null (and {@code workOrderId} null) when the saga
     * isn't in {@code raw_material_shortage} (already un-parked by a prior
     * receipt) or the receipt didn't touch any of the saga's shortage products.
     *
     * <p>Legacy fallback: sagas without a {@code shortageByProductId} stash
     * un-park unconditionally (coarse old-behaviour path; see Javadoc on the
     * implementation for details).
     */
    RecoveryOutcome unparkOrNarrowShortage(UUID sagaId, Map<UUID, BigDecimal> receivedByProductId);

    /**
     * Outcome of {@link #unparkOrNarrowShortage}: the saga's new state plus the
     * work order to re-request reservation for. Both null when nothing changed.
     */
    record RecoveryOutcome(String state, UUID workOrderId) {}

    /**
     * Mark the saga for a manufacturing-completed work order. Idempotent: a
     * saga already in a terminal state (completed / compensated / failed) is
     * left alone. Returns the new state ({@code "completed"} or the unchanged
     * terminal state). Returns null when no saga exists for the WO (logged).
     */
    String applyManufacturingCompleted(UUID workOrderId);
}
