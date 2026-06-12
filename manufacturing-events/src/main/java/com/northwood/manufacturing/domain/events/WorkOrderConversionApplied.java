package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A work order has completed and its standard conversion cost (labour +
 * overhead) is absorbed into WIP (dev-todo §2.42). Emitted once per WO
 * completion from {@code WorkOrderOperationService.onWorkOrderCompleted},
 * alongside {@link SubAssembliesConsumed} — the same completion hook.
 *
 * <p>Finance consumes it to post Dr 1230 WIP / Cr 5250 Conversion Cost Applied.
 * Paired with the material charge (on {@code inventory.RawMaterialsReserved})
 * and the FG receipt (on {@link WorkOrderManufacturingCompleted}, credited at
 * the full standard cost = material + conversion), this makes WIP net to zero
 * per work order.
 *
 * <p>{@code conversionCost} is the total for the completed quantity (per-unit
 * conversion × completedQuantity), computed by manufacturing from the product's
 * active routing × work-centre rates — the same calculation that produced the
 * conversion component of the standard cost, so the legs reconcile. Emitted
 * only when positive; a SKU with no routing / no rates produces no event.
 */
public record WorkOrderConversionApplied(
    UUID eventId,
    UUID aggregateId,          // work_order_id
    String workOrderNumber,
    UUID finishedProductId,
    BigDecimal conversionCost,
    String currencyCode,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderConversionApplied";

    @Override public String eventType() { return EVENT_TYPE; }
}
