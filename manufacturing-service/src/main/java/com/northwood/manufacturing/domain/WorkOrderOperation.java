package com.northwood.manufacturing.domain;

import com.northwood.shared.domain.Assert;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One operation on a work order's routing, snapshotted from
 * {@code routing_operation} at release time. Mutable for completion fields
 * ({@code status}, {@code actualMinutes}, {@code startedAt}, {@code completedAt})
 * — those are advanced via {@link #markCompleted}, not setters; the rest is
 * fixed at release.
 */
public final class WorkOrderOperation {

    private final UUID id;
    private final int operationSequence;
    private final String operationCode;
    private final String description;
    private final UUID workCenterId;
    private final BigDecimal plannedSetupMinutes;
    private final BigDecimal plannedRunMinutes;
    private WorkOrder.OperationStatus status;
    private BigDecimal actualMinutes;
    private Instant startedAt;
    private Instant completedAt;

    /** Constructor used at release time — operation is freshly planned. */
    public WorkOrderOperation(
        UUID id,
        int operationSequence,
        String operationCode,
        String description,
        UUID workCenterId,
        BigDecimal plannedSetupMinutes,
        BigDecimal plannedRunMinutes,
        WorkOrder.OperationStatus status
    ) {
        this(id, operationSequence, operationCode, description, workCenterId,
            plannedSetupMinutes, plannedRunMinutes, status, BigDecimal.ZERO, null, null);
    }

    /** Full constructor — used when reconstituting from the DB. */
    public WorkOrderOperation(
        UUID id,
        int operationSequence,
        String operationCode,
        String description,
        UUID workCenterId,
        BigDecimal plannedSetupMinutes,
        BigDecimal plannedRunMinutes,
        WorkOrder.OperationStatus status,
        BigDecimal actualMinutes,
        Instant startedAt,
        Instant completedAt
    ) {
        Assert.argument(plannedRunMinutes.signum() > 0, "plannedRunMinutes must be > 0");
        this.id = Assert.notNull(id, "id");
        this.operationSequence = operationSequence;
        this.operationCode = Assert.notNull(operationCode, "operationCode");
        this.description = description;
        this.workCenterId = Assert.notNull(workCenterId, "workCenterId");
        this.plannedSetupMinutes = plannedSetupMinutes == null ? BigDecimal.ZERO : plannedSetupMinutes;
        this.plannedRunMinutes = plannedRunMinutes;
        this.status = status;
        this.actualMinutes = actualMinutes == null ? BigDecimal.ZERO : actualMinutes;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    /**
     * Mark this operation skipped. Operator-driven (e.g. an inspection step
     * not needed for this build, or a step a sub-process already covered).
     * From the WO state machine's perspective skipped == completed: it
     * doesn't gate later operations and the WO can advance past it. Package-
     * private; routed through {@link WorkOrder#skipOperation}.
     */
    void markSkipped() {
        Assert.state(status == WorkOrder.OperationStatus.PLANNED || status == WorkOrder.OperationStatus.IN_PROGRESS, "Operation " + operationSequence + " status is " + status.code() + "; cannot skip");
        Instant now = Instant.now();
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.actualMinutes = BigDecimal.ZERO;
        this.status = WorkOrder.OperationStatus.SKIPPED;
        this.completedAt = now;
    }

    /**
     * Mark this operation completed. Package-private — only the {@link WorkOrder}
     * aggregate root may call this so domain invariants (sequence ordering,
     * event emission) stay encapsulated.
     */
    void markCompleted(BigDecimal actualMinutes) {
        Assert.state(status != WorkOrder.OperationStatus.COMPLETED, "Operation " + operationSequence + " is already completed");
        Assert.state(status == WorkOrder.OperationStatus.PLANNED || status == WorkOrder.OperationStatus.IN_PROGRESS, "Operation " + operationSequence + " status is " + status.code() + "; cannot complete");
        Assert.notNull(actualMinutes, "actualMinutes");
        Assert.argument(actualMinutes.signum() >= 0, "actualMinutes must be >= 0");
        Instant now = Instant.now();
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        this.actualMinutes = actualMinutes;
        this.status = WorkOrder.OperationStatus.COMPLETED;
        this.completedAt = now;
    }

    public UUID id()                       { return id; }
    public int operationSequence()         { return operationSequence; }
    public String operationCode()          { return operationCode; }
    public String description()            { return description; }
    public UUID workCenterId()             { return workCenterId; }
    public BigDecimal plannedSetupMinutes(){ return plannedSetupMinutes; }
    public BigDecimal plannedRunMinutes()  { return plannedRunMinutes; }
    public WorkOrder.OperationStatus status() { return status; }
    public BigDecimal actualMinutes()      { return actualMinutes; }
    public Instant startedAt()             { return startedAt; }
    public Instant completedAt()           { return completedAt; }
}
