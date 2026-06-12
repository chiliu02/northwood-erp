package com.northwood.manufacturing.application.dto;

import com.northwood.manufacturing.domain.WorkOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link WorkOrder} for the wire layer. */
public record WorkOrderView(
    UUID workOrderId,
    String workOrderNumber,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    UUID parentWorkOrderId,
    UUID finishedProductId,
    String finishedProductSku,
    String finishedProductName,
    UUID bomHeaderId,
    BigDecimal plannedQuantity,
    String status,
    String materialStatus,
    BigDecimal completedQuantity,
    Instant actualStartAt,
    Instant actualCompletedAt,
    long version,
    List<WorkOrderMaterialView> materials,
    List<WorkOrderOperationView> operations
) {
    public static WorkOrderView from(WorkOrder wo) {
        return new WorkOrderView(
            wo.id().value(),
            wo.workOrderNumber(),
            wo.salesOrderHeaderId(),
            wo.salesOrderLineId(),
            wo.parentWorkOrderId(),
            wo.finishedProductId(),
            wo.finishedProductSku(),
            wo.finishedProductName(),
            wo.bomHeaderId(),
            wo.plannedQuantity(),
            wo.status().code(),
            wo.materialStatus().code(),
            wo.completedQuantity(),
            wo.actualStartAt(),
            wo.actualCompletedAt(),
            wo.version(),
            wo.materials().stream().map(WorkOrderMaterialView::from).toList(),
            wo.operations().stream().map(WorkOrderOperationView::from).toList()
        );
    }
}
