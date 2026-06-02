package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A work order has been released. Carries the snapshotted material and
 * operation lists so downstream consumers (the sales fulfilment saga, the
 * production planning board projection) don't need to query the
 * manufacturing schema.
 *
 * <p>Origin is one of two shapes (enforced by the {@code work_order} CHECK):
 * <ul>
 *   <li><b>Manual</b> — {@code salesOrderHeaderId}, {@code salesOrderLineId},
 *       {@code replenishmentRequestId} all {@code null}.</li>
 *   <li><b>Stock replenishment</b> — {@code replenishmentRequestId}
 *       populated, {@code salesOrderHeaderId}/{@code salesOrderLineId} null.
 *       Inventory's close-the-loop handler consumes this shape (alongside the
 *       sibling {@code manufacturing.ReplenishmentDispatched}); reporting's
 *       production-planning board reads {@code sourceSalesOrderHeaderId}.</li>
 * </ul>
 *
 * <p>The make-to-order shape ({@code salesOrderHeaderId}/{@code salesOrderLineId}
 * populated) was retired — sales-order shortages now flow through inventory's
 * make-to-stock replenishment, so those two fields are always null going
 * forward (kept on the wire + the CHECK set for back-compat).
 *
 * <p>{@code parentWorkOrderId} is {@code null} for top-level work orders
 * and non-null for sub-assembly children spawned by recursion in the release
 * service.
 *
 * <p>{@code sourceSalesOrderHeaderId} is the sales order whose shortage
 * triggered this make-to-stock replenishment WO — non-null only on the
 * top-level replenishment WO (alongside {@code replenishmentRequestId}), null
 * for sub-assembly children and for reorder-point / WO-shortage replenishments.
 * Distinct from {@code salesOrderHeaderId} (the retired make-to-order binding,
 * now always null). Reporting's production-planning board uses it to keep the
 * SO↔WO link the make-to-order path used to carry directly.
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
    UUID replenishmentRequestId,
    UUID sourceSalesOrderHeaderId,
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
