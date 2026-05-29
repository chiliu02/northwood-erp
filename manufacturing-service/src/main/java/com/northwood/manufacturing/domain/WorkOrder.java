package com.northwood.manufacturing.domain;

import com.northwood.manufacturing.domain.events.OperationCompleted;
import com.northwood.manufacturing.domain.events.ReplenishmentDispatched;
import com.northwood.manufacturing.domain.events.WorkOrderCancelled;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.manufacturing.domain.events.WorkOrderCreated.MaterialLine;
import com.northwood.manufacturing.domain.events.WorkOrderCreated.OperationLine;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Wire-format aggregate-type stamped onto {@code manufacturing.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = ManufacturingAggregateTypes.WORK_ORDER;

    /**
     * Human-readable number prefix for new work orders; stamped by
     * {@code WorkOrderReleaseService.release} (manual + parent) and
     * {@code MakeToOrderSagaWorker} (saga-driven release). Pure formatting
     * choice — no consumer dispatches on this value.
     */
    public static final String NUMBER_PREFIX = "WO-";

    /**
     * Character count of the random suffix appended to {@link #NUMBER_PREFIX}
     * when constructing a new work-order number (a
     * {@code UUID.randomUUID().toString().substring(0, …).toUpperCase()}
     * slice). Pairs with the prefix — together they define the full format.
     */
    public static final int NUMBER_SUFFIX_LENGTH = 8;

    /**
     * Work-order lifecycle status. Mirrors the schema CHECK on
     * {@code manufacturing.work_order.status}. Lifecycle:
     * {@code RELEASED} / {@code PLANNED} → {@code IN_PROGRESS} →
     * {@code COMPLETED}; {@code CANCELLED} and {@code CLOSED} are terminal
     * side rails.
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java. */
        PLANNED("planned"),
        /** Schema-prep — not currently produced by Java. */
        MATERIAL_CHECK_PENDING("material_check_pending"),
        /** Schema-prep — not currently produced by Java. */
        WAITING_FOR_MATERIALS("waiting_for_materials"),
        RELEASED("released"),
        IN_PROGRESS("in_progress"),
        /** Schema-prep — not currently produced by Java. */
        PARTIALLY_COMPLETED("partially_completed"),
        COMPLETED("completed"),
        CLOSED("closed"),
        CANCELLED("cancelled"),
        /** Schema-prep — not currently produced by Java. */
        BLOCKED("blocked");

        private final String dbValue;

        Status(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Status fromDb(String value) {
            for (Status s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("work_order status", value);
        }
    }

    /**
     * Material-reservation status (header-level secondary field). Mirrors the
     * schema CHECK on {@code manufacturing.work_order.material_status}.
     */
    public enum MaterialStatus {
        /** Schema-prep — not currently produced by Java. */
        NOT_CHECKED("not_checked"),
        RESERVATION_PENDING("reservation_pending"),
        RESERVED("reserved"),
        PARTIALLY_RESERVED("partially_reserved"),
        SHORTAGE("shortage"),
        /** Schema-prep — not currently produced by Java. */
        ISSUED("issued");

        private final String dbValue;

        MaterialStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static MaterialStatus fromDb(String value) {
            for (MaterialStatus s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("work_order material_status", value);
        }
    }

    /**
     * Per-material-line status. Mirrors the schema CHECK on
     * {@code manufacturing.work_order_material.status}. Tracked on each
     * {@link WorkOrderMaterial} line; the header's {@link MaterialStatus}
     * is the aggregate-level rollup of these.
     */
    public enum MaterialLineStatus {
        REQUIRED("required"),
        RESERVED("reserved"),
        PARTIALLY_RESERVED("partially_reserved"),
        SHORTAGE("shortage"),
        /** Schema-prep — not currently produced by Java. */
        ISSUED("issued");

        private final String dbValue;

        MaterialLineStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static MaterialLineStatus fromDb(String value) {
            for (MaterialLineStatus s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("work_order_material status", value);
        }
    }

    /**
     * Per-operation status. Mirrors the schema CHECK on
     * {@code manufacturing.work_order_operation.status}. Lifecycle:
     * {@code PLANNED} → {@code IN_PROGRESS} → {@code COMPLETED};
     * {@code SKIPPED} is the side rail driven by operator action.
     */
    public enum OperationStatus {
        PLANNED("planned"),
        IN_PROGRESS("in_progress"),
        COMPLETED("completed"),
        SKIPPED("skipped");

        private final String dbValue;

        OperationStatus(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static OperationStatus fromDb(String value) {
            for (OperationStatus s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("work_order_operation status", value);
        }
    }

    private final WorkOrderId id;
    private final String workOrderNumber;
    private final UUID salesOrderHeaderId;
    private final UUID salesOrderLineId;
    private final UUID replenishmentRequestId;
    private final UUID parentWorkOrderId;
    private final UUID finishedProductId;
    private final String finishedProductSku;
    private final String finishedProductName;
    private final UUID bomHeaderId;
    private final BigDecimal plannedQuantity;
    private Status status;
    private MaterialStatus materialStatus;
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
        return releaseInternal(
            workOrderNumber, salesOrderHeaderId, salesOrderLineId,
            /* replenishmentRequestId */ null,
            parentWorkOrderId, finishedProductId, finishedProductSku, finishedProductName,
            bomHeaderId, plannedQuantity, materials, operations,
            /* sourceSalesOrderHeaderId */ null
        );
    }

    /**
     * §2.35 Slice C: release a new work order against an
     * {@link com.northwood.inventory.domain.ReplenishmentRequest}
     * (a stock-replenishment WO; no sales-order line). Emits BOTH
     * {@link WorkOrderCreated} (with {@code replenishmentRequestId} populated)
     * AND {@link ReplenishmentDispatched} in the same pending-events list so
     * inventory's close-the-loop handler sees the dispatch atomically with
     * the WO creation.
     */
    public static WorkOrder releaseForReplenishment(
        String workOrderNumber,
        UUID replenishmentRequestId,
        UUID sourceSalesOrderHeaderId,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        UUID bomHeaderId,
        BigDecimal plannedQuantity,
        List<WorkOrderMaterial> materials,
        List<WorkOrderOperation> operations
    ) {
        Assert.notNull(replenishmentRequestId, "replenishmentRequestId");
        WorkOrder wo = releaseInternal(
            workOrderNumber,
            /* salesOrderHeaderId */ null,
            /* salesOrderLineId   */ null,
            replenishmentRequestId,
            /* parentWorkOrderId  */ null,
            finishedProductId, finishedProductSku, finishedProductName,
            bomHeaderId, plannedQuantity, materials, operations,
            sourceSalesOrderHeaderId
        );
        wo.pendingEvents.add(new ReplenishmentDispatched(
            UUID.randomUUID(),
            wo.id.value(),
            replenishmentRequestId,
            Instant.now()
        ));
        return wo;
    }

    private static WorkOrder releaseInternal(
        String workOrderNumber,
        UUID salesOrderHeaderId,
        UUID salesOrderLineId,
        UUID replenishmentRequestId,
        UUID parentWorkOrderId,
        UUID finishedProductId,
        String finishedProductSku,
        String finishedProductName,
        UUID bomHeaderId,
        BigDecimal plannedQuantity,
        List<WorkOrderMaterial> materials,
        List<WorkOrderOperation> operations,
        UUID sourceSalesOrderHeaderId
    ) {
        Assert.argument(plannedQuantity != null && plannedQuantity.signum() > 0, "plannedQuantity must be > 0");
        Assert.notEmpty(operations, "at least one operation is required to release a work order");
        WorkOrderId id = WorkOrderId.newId();
        WorkOrder wo = new WorkOrder(
            id,
            Assert.notNull(workOrderNumber, "workOrderNumber"),
            salesOrderHeaderId,
            salesOrderLineId,
            replenishmentRequestId,
            parentWorkOrderId,
            Assert.notNull(finishedProductId, "finishedProductId"),
            Assert.notNull(finishedProductSku, "finishedProductSku"),
            Assert.notNull(finishedProductName, "finishedProductName"),
            Assert.notNull(bomHeaderId, "bomHeaderId"),
            plannedQuantity,
            Status.RELEASED,
            MaterialStatus.RESERVATION_PENDING,
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
            replenishmentRequestId,
            sourceSalesOrderHeaderId,
            Instant.now()
        ));
        return wo;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static WorkOrder reconstitute(
        WorkOrderId id, String workOrderNumber,
        UUID salesOrderHeaderId, UUID salesOrderLineId,
        UUID replenishmentRequestId, UUID parentWorkOrderId,
        UUID finishedProductId, String finishedProductSku, String finishedProductName,
        UUID bomHeaderId, BigDecimal plannedQuantity,
        Status status, MaterialStatus materialStatus,
        BigDecimal completedQuantity, Instant actualStartAt, Instant actualCompletedAt,
        long version,
        List<WorkOrderMaterial> materials, List<WorkOrderOperation> operations
    ) {
        return new WorkOrder(
            id, workOrderNumber,
            salesOrderHeaderId, salesOrderLineId,
            replenishmentRequestId, parentWorkOrderId,
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
        UUID salesOrderHeaderId, UUID salesOrderLineId,
        UUID replenishmentRequestId, UUID parentWorkOrderId,
        UUID finishedProductId, String finishedProductSku, String finishedProductName,
        UUID bomHeaderId, BigDecimal plannedQuantity,
        Status status, MaterialStatus materialStatus,
        BigDecimal completedQuantity, Instant actualStartAt, Instant actualCompletedAt,
        long version,
        List<WorkOrderMaterial> materials, List<WorkOrderOperation> operations
    ) {
        this.id = id;
        this.workOrderNumber = workOrderNumber;
        this.salesOrderHeaderId = salesOrderHeaderId;
        this.salesOrderLineId = salesOrderLineId;
        this.replenishmentRequestId = replenishmentRequestId;
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
        Assert.state(status != Status.COMPLETED && status != Status.CLOSED && status != Status.CANCELLED, "Work order " + id.value() + " is " + status.dbValue() + "; cannot complete operations");
        WorkOrderOperation target = operations.stream()
            .filter(o -> o.operationSequence() == sequence)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No operation with sequence " + sequence + " on work order " + id.value()
            ));
        for (WorkOrderOperation earlier : operations) {
            Assert.state(earlier.operationSequence() >= sequence
                || earlier.status() == OperationStatus.COMPLETED
                || earlier.status() == OperationStatus.SKIPPED, "Cannot complete operation " + sequence
                        + " before operation " + earlier.operationSequence()
                        + " (status=" + earlier.status().dbValue() + ")");
        }

        target.markCompleted(actualMinutes);

        if (status == Status.RELEASED || status == Status.PLANNED) {
            this.status = Status.IN_PROGRESS;
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
        if (status == Status.COMPLETED || status == Status.CLOSED || status == Status.CANCELLED) {
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
        if (status == Status.COMPLETED || status == Status.CLOSED || status == Status.CANCELLED) {
            return;
        }
        this.status = Status.CANCELLED;
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
    public void applyReservationOutcome(MaterialStatus newMaterialStatus) {
        Assert.argument(newMaterialStatus == MaterialStatus.RESERVED
            || newMaterialStatus == MaterialStatus.PARTIALLY_RESERVED
            || newMaterialStatus == MaterialStatus.SHORTAGE, "Unknown material status: " + newMaterialStatus
                    + " (must be one of " + MaterialStatus.RESERVED + " / "
                    + MaterialStatus.PARTIALLY_RESERVED + " / " + MaterialStatus.SHORTAGE + ")");
        if (status == Status.COMPLETED || status == Status.CLOSED || status == Status.CANCELLED) {
            return;
        }
        if (newMaterialStatus == this.materialStatus) {
            return;
        }
        this.materialStatus = newMaterialStatus;
    }

    private void transitionToCompleted() {
        this.status = Status.COMPLETED;
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
        Assert.state(status != Status.COMPLETED && status != Status.CLOSED && status != Status.CANCELLED, "Work order " + id.value() + " is " + status.dbValue() + "; cannot skip operations");
        WorkOrderOperation target = operations.stream()
            .filter(o -> o.operationSequence() == sequence)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "No operation with sequence " + sequence + " on work order " + id.value()
            ));
        for (WorkOrderOperation earlier : operations) {
            Assert.state(earlier.operationSequence() >= sequence
                || earlier.status() == OperationStatus.COMPLETED
                || earlier.status() == OperationStatus.SKIPPED, "Cannot skip operation " + sequence
                        + " before operation " + earlier.operationSequence()
                        + " (status=" + earlier.status().dbValue() + ")");
        }

        target.markSkipped();

        if (status == Status.RELEASED || status == Status.PLANNED) {
            this.status = Status.IN_PROGRESS;
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
            o.status() == OperationStatus.COMPLETED || o.status() == OperationStatus.SKIPPED);
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
    public UUID replenishmentRequestId()          { return replenishmentRequestId; }
    public UUID parentWorkOrderId()               { return parentWorkOrderId; }
    public UUID finishedProductId()               { return finishedProductId; }
    public String finishedProductSku()            { return finishedProductSku; }
    public String finishedProductName()           { return finishedProductName; }
    public UUID bomHeaderId()                     { return bomHeaderId; }
    public BigDecimal plannedQuantity()           { return plannedQuantity; }
    public Status status()                        { return status; }
    public MaterialStatus materialStatus()        { return materialStatus; }
    public BigDecimal completedQuantity()         { return completedQuantity; }
    public Instant actualStartAt()                { return actualStartAt; }
    public Instant actualCompletedAt()            { return actualCompletedAt; }
    public long version()                         { return version; }
    public List<WorkOrderMaterial> materials()    { return List.copyOf(materials); }
    public List<WorkOrderOperation> operations()  { return List.copyOf(operations); }
}
