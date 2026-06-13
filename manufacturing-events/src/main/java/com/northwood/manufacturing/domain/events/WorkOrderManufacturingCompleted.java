package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.domain.Quantity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The last operation on a work order has completed AND, if the work order has
 * sub-assembly children, all children are completed too. Drives the
 * manufacturing-side work-order saga to {@code completed} and the
 * sales-side fulfilment saga to {@code ready_to_ship}.
 *
 * <p>{@code parentWorkOrderId} is {@code null} for top-level work orders.
 * Sales' handler filters out non-null entries so internal sub-assembly
 * completions don't leak into the multi-line tracker.
 *
 * <p>{@link Quantity} isn't used here — we send raw {@link BigDecimal} for the
 * completed quantity to keep the wire format flat for cross-service consumers.
 *
 * <p>{@code replenishmentRequestId} is non-null only when this WO was released
 * to fulfil an inventory replenishment (it forwards the WO's own
 * {@code replenishmentRequestId}). Inventory's production-confirmation handler
 * reads it to resolve the originating {@code ReplenishmentRequest} and
 * dispatch-and-fulfil it self-sufficiently, so this event's arrival order
 * relative to {@code ReplenishmentDispatched} no longer matters.
 */
public record WorkOrderManufacturingCompleted(
    UUID eventId,
    UUID aggregateId,
    String workOrderNumber,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    UUID parentWorkOrderId,
    UUID replenishmentRequestId,
    UUID finishedProductId,
    String finishedProductSku,
    BigDecimal completedQuantity,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderManufacturingCompleted";

    @Override public String eventType() { return EVENT_TYPE; }
}
