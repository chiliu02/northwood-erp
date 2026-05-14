package com.northwood.manufacturing.domain;

import com.northwood.manufacturing.domain.events.OperationCompleted;
import com.northwood.manufacturing.domain.events.WorkOrderCancelled;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderCreated.MaterialLine;
import com.northwood.manufacturing.domain.events.WorkOrderCreated.OperationLine;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a manufacturing work order: header + material requirements
 * + planned operations. {@link #release} snapshots a BOM and a Routing into
 * the work order at creation time so subsequent edits to the templates don't
 * retroactively affect this WO. {@link #completeOperation} advances operations
 * through their state machine and, when the last operation lands AND no child
 * sub-assembly work orders are still pending, transitions the work order itself
 * to {@code completed}. {@link #onChildCompleted} is the matching trigger for
 * the cross-WO case: a child finishing might be the event that lets a parent
 * (whose own ops were already done) finally complete.
 */
public final class WorkOrder {

    // ------------------------------------------------------------
    // Status constants — wire-format strings stored in
    // manufacturing.work_order.status. Lifecycle: released → in_progress →
    // completed; cancelled and closed are terminal side rails.
    // ------------------------------------------------------------
    public static final String RELEASED = "released";
    public static final String PLANNED = "planned";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String CLOSED = "closed";
    public static final String CANCELLED = "cancelled";

    /** Material status (separate field from status): pending / reserved / partially_reserved / shortage / consumed. */
    public static final String MATERIAL_RESERVATION_PENDING = "reservation_pending";
    public static final String MATERIAL_RESERVED = "reserved";
    public static final String MATERIAL_PARTIALLY_RESERVED = "partially_reserved";
    public static final String MATERIAL_SHORTAGE = "shortage";

    private final WorkOrderId id;
    private final String workOrderNumber;
    private final UUID salesOrderHeaderId;
    private final UUID salesOrderLineId;
    private final UUID parentWorkOrderId;
    private final UUID finishedProductId;
    private final String finishedProductSku;
    private final String finishedProductName;
    private final UUID bomHeaderId;
    private final BigDecimal plannedQuantity;
    private String status;
    private String materialStatus;
    private BigDecimal completedQuantity;
    private Instant actualStartAt;
    private Instant actualCompletedAt;
    private final long version;
    private final List<WorkOrderMaterial> materials;
    private final List<WorkOrderOperation> operations;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: release a new work order against a sales-order line, snapshotting BOM + routing. */
    public static WorkOrder release(
        String workOrderNumber,
        UUID salesOrderHeaderId,
        UUID salesOrderLineId,
        UUID parentWorkOrderId,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        UUID bomHeaderId,
        BigDecimal plannedQuantity,
        List<WorkOrderMaterial> materials,
        List<WorkOrderOperation> operations
    ) {
        if (plannedQuantity == null || plannedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("plannedQuantity must be > 0");
        }
        if (operations == null || operations.isEmpty()) {
            throw new IllegalArgumentException("at least one operation is required to release a work order");
        }
        WorkOrderId id = WorkOrderId.newId();
        WorkOrder wo = new WorkOrder(
            id,
            Objects.requireNonNull(workOrderNumber),
            salesOrderHeaderId,
            salesOrderLineId,
            parentWorkOrderId,
            Objects.requireNonNull(finishedProductId),
            Objects.requireNonNull(finishedProductSku),
            Objects.requireNonNull(finishedProductName),
            Objects.requireNonNull(bomHeaderId),
            plannedQuantity,
            "released",
            "reservation_pending",
            BigDecimal.ZERO,
            null,
            null,
            0L,
            new ArrayList<>(materials == null ? List.of() : materials),
            new ArrayList<>(operations)
        );

        List<MaterialLine> materialLines = new ArrayList<>();
        for (WorkOrderMaterial m : wo.materials) {
            materialLines.add(new MaterialLine(
                m.id(), m.componentProductId(), m.componentSku(), m.componentName(), m.requiredQuantity()
            ));
        }
        List<OperationLine> operationLines = new ArrayList<>();
        for (WorkOrderOperation o : operations) {
            operationLines.add(new OperationLine(
                o.id(), o.operationSequence(), o.operationCode(), o.description(),
                o.workCenterId(), o.plannedSetupMinutes(), o.plannedRunMinutes()
            ));
        }
        wo.pendingEvents.add(new WorkOrderCreated(
            UUID.randomUUID(),
            id.value(),
            workOrderNumber,
            salesOrderHeaderId,
            salesOrderLineId,
            parentWorkOrderId,
            finishedProductId,
            finishedProductSku,
            finishedProductName,
            bomHeaderId,
            plannedQuantity,
            materialLines,
            operationLines,
            Instant.now()
        ));
        return wo;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static WorkOrder reconstitute(
        WorkOrderId id, String workOrderNumber,
        UUID salesOrderHeaderId, UUID salesOrderLineId, UUID parentWorkOrderId,
        UUID finishedProductId, String finishedProductSku, String finishedProductName,
        UUID bomHeaderId, BigDecimal plannedQuantity,
        String status, String materialStatus,
        BigDecimal completedQuantity, Instant actualStartAt, Instant actualCompletedAt,
        long version,
        List<WorkOrderMaterial> materials, List<WorkOrderOperation> operations
    ) {
        return new WorkOrder(
            id, workOrderNumber,
            salesOrderHeaderId, salesOrderLineId, parentWorkOrderId,
            finishedProductId, finishedProductSku, finishedProductName,
            bomHeaderId, plannedQuantity,
            status, materialStatus,
            completedQuantity == null ? BigDecimal.ZERO : completedQuantity,
            actualStartAt, actualCompletedAt,
            version,
            new ArrayList<>(materials), new ArrayList<>(operations)
        );
    }

    private WorkOrder(
        WorkOrderId id, String workOrderNumber,
        UUID salesOrderHeaderId, UUID salesOrderLineId, UUID parentWorkOrderId,
        UUID finishedProductId, String finishedProductSku, String finishedProductName,
        UUID bomHeaderId, BigDecimal plannedQuantity,
        String status, String materialStatus,
        BigDecimal completedQuantity, Instant actualStartAt, Instant actualCompletedAt,
        long version,
        List<WorkOrderMaterial> materials, List<WorkOrderOperation> operations
    ) {
        this.id = id;
        this.workOrderNumber = workOrderNumber;
        this.salesOrderHeaderId = salesOrderHeaderId;
        this.salesOrderLineId = salesOrderLineId;
        this.parentWorkOrderId = parentWorkOrderId;
        this.finishedProductId = finishedProductId;
        this.finishedProductSku = finishedProductSku;
        this.finishedProductName = finishedProductName;
        this.bomHeaderId = bomHeaderId;
        this.plannedQuantity = plannedQuantity;
        this.status = status;
        this.materialStatus = materialStatus;
        this.completedQuantity = completedQuantity;
        this.actualStartAt = actualStartAt;
        this.actualCompletedAt = actualCompletedAt;
        this.version = version;
        this.materials = materials;
        this.operations = operations;
    }

    /**
     * Complete one operation by sequence number. Operations must complete in
     * strictly increasing order — earlier ops must already be {@code completed}
     * before a later one can finish. When the last operation lands AND
     * {@code noPendingChildren} is true, the work order itself transitions to
     * {@code completed} and emits {@link WorkOrderManufacturingCompleted}.
     * If children are still pending, the operation completes but the WO holds
     * at {@code in_progress}; the matching {@link #onChildCompleted} call later
     * (when the last child finishes) will release the gate.
     */
    public void completeOperation(int sequence, BigDecimal actualMinutes, boolean noPendingChildren) {
        if (COMPLETED.equals(status) || CLOSED.equals(status) || CANCELLED.equals(status)) {
            throw new IllegalStateException(
                "Work order " + id.value() + " is " + status + "; cannot complete operations"
            );
        }
        WorkOrderOperation target = operations.stream()
            .filter(o -> o.operationSequence() == sequence)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No operation with sequence " + sequence + " on work order " + id.value()
            ));
        for (WorkOrderOperation earlier : operations) {
            if (earlier.operationSequence() < sequence
                && !COMPLETED.equals(earlier.status())
                && !WorkOrderOperation.SKIPPED.equals(earlier.status())) {
                throw new IllegalStateException(
                    "Cannot complete operation " + sequence
                        + " before operation " + earlier.operationSequence()
                        + " (status=" + earlier.status() + ")"
                );
            }
        }

        target.markCompleted(actualMinutes);

        if (RELEASED.equals(status) || PLANNED.equals(status)) {
            this.status = IN_PROGRESS;
            if (this.actualStartAt == null) {
                this.actualStartAt = Instant.now();
            }
        }

        pendingEvents.add(new OperationCompleted(
            UUID.randomUUID(),
            id.value(),
            target.id(),
            target.operationSequence(),
            target.operationCode(),
            target.plannedRunMinutes(),
            target.actualMinutes(),
            salesOrderHeaderId,
            salesOrderLineId,
            Instant.now()
        ));

        if (allOperationsCompleted() && noPendingChildren) {
            transitionToCompleted();
        }
    }

    /**
     * Trigger from a child sub-assembly WO finishing. If this WO has its own
     * operations all done already, the child's completion is the missing piece
     * — this WO transitions to {@code completed} now. Otherwise it's a no-op:
     * the parent's last operation will handle completion when it lands.
     *
     * <p>{@code nowAllChildrenComplete} is the application service's verdict
     * after counting unfinished children (must be true to proceed). The
     * aggregate trusts the caller; it doesn't go fetch children itself.
     */
    public void onChildCompleted(boolean nowAllChildrenComplete) {
        if (COMPLETED.equals(status) || CLOSED.equals(status) || CANCELLED.equals(status)) {
            return;
        }
        if (allOperationsCompleted() && nowAllChildrenComplete) {
            transitionToCompleted();
        }
    }

    /**
     * Cancel this work order. Idempotent against already-terminal states (a
     * WO already in {@code completed} / {@code closed} / {@code cancelled}
     * silently no-ops without emitting). Emits {@link WorkOrderCancelled} so
     * inventory can release the raw-material reservation tied to this WO and
     * reporting can flip the production-board row.
     *
     * <p>Hard cancel by design (dev-todo §1.1): WIP is written off — the WO
     * goes straight to {@code cancelled} regardless of how many operations
     * have completed. Soft-cancel ("let production finish then scrap") is a
     * future polish.
     */
    public void cancel(String reason) {
        if (COMPLETED.equals(status) || CLOSED.equals(status) || CANCELLED.equals(status)) {
            return;
        }
        this.status = CANCELLED;
        this.actualCompletedAt = Instant.now();
        pendingEvents.add(new WorkOrderCancelled(
            UUID.randomUUID(),
            id.value(),
            parentWorkOrderId,
            salesOrderHeaderId,
            reason,
            Instant.now()
        ));
    }

    /**
     * Project the outcome of the raw-material reservation back onto this WO.
     * Called by {@code RawMaterialsReservedHandler} after {@code
     * inventory.RawMaterialsReserved} lands. The make-to-order saga already
     * tracks the same outcome on its state machine; this method gives a UI
     * reading the WO directly (production-board detail, /api/work-orders-cmd/
     * {id}) access to the same signal without joining across services.
     *
     * <p>Idempotent: no-op when the new value matches the current
     * {@code materialStatus}, no-op when the WO is in a terminal status
     * (cancelled / completed / closed). Emits no event — this is a local
     * projection, not a domain transition; the {@code RawMaterialsReserved}
     * inbox event is the wire-level fact.
     */
    public void applyReservationOutcome(String newMaterialStatus) {
        if (!MATERIAL_RESERVED.equals(newMaterialStatus)
            && !MATERIAL_PARTIALLY_RESERVED.equals(newMaterialStatus)
            && !MATERIAL_SHORTAGE.equals(newMaterialStatus)) {
            throw new IllegalArgumentException(
                "Unknown material status: " + newMaterialStatus
                    + " (must be one of " + MATERIAL_RESERVED + " / "
                    + MATERIAL_PARTIALLY_RESERVED + " / " + MATERIAL_SHORTAGE + ")"
            );
        }
        if (COMPLETED.equals(status) || CLOSED.equals(status) || CANCELLED.equals(status)) {
            return;
        }
        if (newMaterialStatus.equals(this.materialStatus)) {
            return;
        }
        this.materialStatus = newMaterialStatus;
    }

    private void transitionToCompleted() {
        this.status = COMPLETED;
        this.completedQuantity = plannedQuantity;
        this.actualCompletedAt = Instant.now();
        pendingEvents.add(new WorkOrderManufacturingCompleted(
            UUID.randomUUID(),
            id.value(),
            workOrderNumber,
            salesOrderHeaderId,
            salesOrderLineId,
            parentWorkOrderId,
            finishedProductId,
            finishedProductSku,
            completedQuantity,
            Instant.now()
        ));
    }

    /**
     * Skip an operation by sequence number. Same ordering invariant as
     * {@link #completeOperation} — earlier operations must be done (completed
     * or skipped) before a later one can be skipped. From the WO state
     * machine's perspective skipped == completed: the next op can run, and
     * if this is the last op the WO transitions to {@code completed}.
     * Skipped ops emit no {@code OperationCompleted} (they didn't produce
     * the standard outcome event); the WO-level {@code WorkOrderManufacturingCompleted}
     * still fires when the whole WO is done.
     */
    public void skipOperation(int sequence, String reason, boolean noPendingChildren) {
        if (COMPLETED.equals(status) || CLOSED.equals(status) || CANCELLED.equals(status)) {
            throw new IllegalStateException(
                "Work order " + id.value() + " is " + status + "; cannot skip operations"
            );
        }
        WorkOrderOperation target = operations.stream()
            .filter(o -> o.operationSequence() == sequence)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No operation with sequence " + sequence + " on work order " + id.value()
            ));
        for (WorkOrderOperation earlier : operations) {
            if (earlier.operationSequence() < sequence
                && !COMPLETED.equals(earlier.status())
                && !WorkOrderOperation.SKIPPED.equals(earlier.status())) {
                throw new IllegalStateException(
                    "Cannot skip operation " + sequence
                        + " before operation " + earlier.operationSequence()
                        + " (status=" + earlier.status() + ")"
                );
            }
        }

        target.markSkipped();

        if (RELEASED.equals(status) || PLANNED.equals(status)) {
            this.status = IN_PROGRESS;
            if (this.actualStartAt == null) {
                this.actualStartAt = Instant.now();
            }
        }

        if (allOperationsCompleted() && noPendingChildren) {
            transitionToCompleted();
        }
    }

    private boolean allOperationsCompleted() {
        return operations.stream().allMatch(o ->
            WorkOrderOperation.COMPLETED.equals(o.status()) || WorkOrderOperation.SKIPPED.equals(o.status()));
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public WorkOrderId id()                       { return id; }
    public String workOrderNumber()               { return workOrderNumber; }
    public UUID salesOrderHeaderId()              { return salesOrderHeaderId; }
    public UUID salesOrderLineId()                { return salesOrderLineId; }
    public UUID parentWorkOrderId()               { return parentWorkOrderId; }
    public UUID finishedProductId()               { return finishedProductId; }
    public String finishedProductSku()            { return finishedProductSku; }
    public String finishedProductName()           { return finishedProductName; }
    public UUID bomHeaderId()                     { return bomHeaderId; }
    public BigDecimal plannedQuantity()           { return plannedQuantity; }
    public String status()                        { return status; }
    public String materialStatus()                { return materialStatus; }
    public BigDecimal completedQuantity()         { return completedQuantity; }
    public Instant actualStartAt()                { return actualStartAt; }
    public Instant actualCompletedAt()            { return actualCompletedAt; }
    public long version()                         { return version; }
    public List<WorkOrderMaterial> materials()    { return List.copyOf(materials); }
    public List<WorkOrderOperation> operations()  { return List.copyOf(operations); }
}
