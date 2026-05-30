package com.northwood.manufacturing.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for the {@link WorkOrder} aggregate. Saves header + materials +
 * operations and drains pending domain events to the outbox in the same
 * transaction.
 */
public interface WorkOrderRepository {

    Optional<WorkOrder> findById(WorkOrderId id);

    void save(WorkOrder workOrder);

    /**
     * Count children of {@code parentWorkOrderId} that are not yet in a
     * terminal status ({@code completed}, {@code closed}, {@code cancelled}).
     * Used by the parent-on-children completion gate.
     */
    int countUnfinishedChildren(UUID parentWorkOrderId);

    /**
     * Same as {@link #countUnfinishedChildren} but excludes {@code excludeChildId}
     * — for the case where a child has just transitioned to {@code completed}
     * within the current transaction and the count needs to reflect post-save
     * state without a flush dance.
     */
    int countUnfinishedChildrenExcluding(UUID parentWorkOrderId, UUID excludeChildId);

    /**
     * Return immediate children of {@code parentWorkOrderId} that are in
     * {@code completed} status, with their finished-product id and the
     * completed quantity. Used by {@code WorkOrderOperationService} to emit
     * {@code SubAssembliesConsumed} when a parent WO completes.
     */
    List<CompletedChild> findCompletedChildren(UUID parentWorkOrderId);

    record CompletedChild(UUID workOrderId, UUID finishedProductId, BigDecimal completedQuantity) {}
}
