package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A work order has been released against a sales-order line. Carries the
 * snapshotted material and operation lists so downstream consumers (the sales
 * fulfilment saga, the production planning board projection) don't need to
 * query the manufacturing schema.
 *
 * <p>{@code parentWorkOrderId} is {@code null} for top-level work orders
 * (released directly against a sales-order line) and non-null for sub-assembly
 * children spawned by recursion in the release service. Sales' fulfilment saga
 * filters out non-null entries — it only tracks one work order per
 * sales-order-line, the parent.
 */
public record WorkOrderCreated(
    UUID eventId,
    UUID aggregateId,
    String workOrderNumber,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    UUID parentWorkOrderId,
    UUID finishedProductId,
    String finishedProductSku,
    String finishedProductName,
    UUID bomHeaderId,
    BigDecimal plannedQuantity,
    List<MaterialLine> materials,
    List<OperationLine> operations,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderCreated";

    @Override public String eventType() { return EVENT_TYPE; }

    public record MaterialLine(
        UUID workOrderMaterialId,
        UUID componentProductId,
        String componentSku,
        String componentName,
        BigDecimal requiredQuantity
    ) {}

    public record OperationLine(
        UUID workOrderOperationId,
        int operationSequence,
        String operationCode,
        String description,
        UUID workCenterId,
        BigDecimal plannedSetupMinutes,
        BigDecimal plannedRunMinutes
    ) {}
}
