package com.northwood.manufacturing.domain.saga;

import com.northwood.manufacturing.domain.ManufacturingAggregateTypes;
import com.northwood.shared.domain.saga.SagaInstance;
import com.northwood.shared.domain.Assert;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Saga state row for the work-order lifecycle: raw-material reservation →
 * (shortage recovery) → manufacturing completion, including sub-assembly
 * recursion.
 *
 * <p>The saga is entered at {@code work_order_created} (the WO already exists,
 * created by {@code WorkOrderReleaseService}); the worker drives
 * {@code work_order_created → raw_material_reservation_requested → …}.
 *
 * <p>§2.37 Slice 3 retired the {@code started} entry state: it only existed for
 * the sales-driven make-to-order path (a {@code ManufacturingRequested} seeded
 * a saga at {@code started}, which then released the WO). Sales-order shortages
 * now route through inventory's replenishment (make-to-stock), so every saga is
 * seeded directly at {@code work_order_created}. (Saga is now a misnomer — it
 * serves make-to-stock too; §2.39 tracks the rename.)
 */
public final class WorkOrderSaga extends SagaInstance {

    /**
     * Wire-format aggregate-type reserved for events this saga emits under its
     * own identity. Currently unused — the worker's sole outbox emission
     * ({@code RawMaterialReservationRequested}) is stamped under
     * {@link com.northwood.manufacturing.domain.WorkOrder#AGGREGATE_TYPE}
     * because it naturally belongs to the just-created WorkOrder's stream.
     * Declared for symmetry with {@code SalesOrderFulfilmentSaga} and as the
     * stable call site for any future saga-originated commands.
     */
    public static final String AGGREGATE_TYPE = ManufacturingAggregateTypes.WORK_ORDER_SAGA;

    // ------------------------------------------------------------
    // State constants — Java-side ergonomic for the wire-format strings
    // stored in manufacturing.work_order_saga.saga_state. The DB CHECK
    // and event payloads keep the underlying strings as canonical form.
    // ------------------------------------------------------------
    public static final String WORK_ORDER_CREATED = "work_order_created";
    public static final String RAW_MATERIAL_RESERVATION_REQUESTED = "raw_material_reservation_requested";
    public static final String RAW_MATERIALS_RESERVED = "raw_materials_reserved";
    public static final String RAW_MATERIAL_SHORTAGE = "raw_material_shortage";
    public static final String COMPLETED = "completed";
    public static final String COMPENSATING = "compensating";
    public static final String COMPENSATED = "compensated";
    public static final String FAILED = "failed";

    private static final Set<String> TERMINAL_STATES = Set.of(COMPLETED, COMPENSATED, FAILED);

    /**
     * Every state this saga's code can transition into. Cross-checked at
     * service startup against the schema CHECK on
     * {@code manufacturing.work_order_saga.saga_state} via
     * {@code SagaStateInvariantChecker}.
     */
    public static final Set<String> ALL_STATES = Set.of(
        WORK_ORDER_CREATED,
        RAW_MATERIAL_RESERVATION_REQUESTED,
        RAW_MATERIALS_RESERVED, RAW_MATERIAL_SHORTAGE,
        COMPLETED,
        COMPENSATING, COMPENSATED,
        FAILED
    );

    private final UUID salesOrderHeaderId;
    private final UUID salesOrderLineId;
    private UUID workOrderId;

    public WorkOrderSaga(
        UUID sagaId,
        UUID salesOrderHeaderId,
        UUID salesOrderLineId,
        UUID workOrderId,
        String state,
        String currentStep,
        String lastError,
        int retryCount,
        Instant nextRetryAt,
        String leaseOwner,
        Instant leaseExpiresAt,
        long version,
        String dataJson,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
    ) {
        super(sagaId, state, currentStep, lastError, retryCount, nextRetryAt,
              leaseOwner, leaseExpiresAt, version, dataJson, createdAt, updatedAt, completedAt);
        this.salesOrderHeaderId = salesOrderHeaderId;
        this.salesOrderLineId = salesOrderLineId;
        this.workOrderId = workOrderId;
    }

    /**
     * Factory for WO-lifecycle sagas: the WO is already created by the
     * recursing release service, so the saga starts at
     * {@code work_order_created} and the worker's next tick picks it up for
     * raw-material reservation. {@code workOrderId} is non-null and
     * pre-attached. Used for the root stock-replenishment WO and every
     * sub-assembly child (all make-to-stock; sales-order pair null).
     */
    public static WorkOrderSaga attachedToWorkOrder(
        UUID salesOrderHeaderId,
        UUID salesOrderLineId,
        UUID workOrderId,
        String dataJson
    ) {
        Instant now = Instant.now();
        return new WorkOrderSaga(
            UUID.randomUUID(),
            salesOrderHeaderId,
            salesOrderLineId,
            workOrderId,
            WORK_ORDER_CREATED,
            "wait_for_raw_material_reservation",
            null,
            0,
            now,
            null,
            null,
            0L,
            dataJson == null ? "{}" : dataJson,
            now,
            now,
            null
        );
    }

    @Override
    public Set<String> terminalStates() {
        return TERMINAL_STATES;
    }

    /** Set once the saga has decided which work order it owns. */
    public void attachWorkOrder(UUID workOrderId) {
        Assert.state(this.workOrderId == null || this.workOrderId.equals(workOrderId), "Saga " + sagaId() + " already attached to work_order=" + this.workOrderId);
        this.workOrderId = workOrderId;
    }

    public UUID salesOrderHeaderId() { return salesOrderHeaderId; }
    public UUID salesOrderLineId()   { return salesOrderLineId; }
    public UUID workOrderId()        { return workOrderId; }
}
