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
 * <p>Finance consumes it to post Dr 1230 WIP / Cr 5250 Conversion Cost Applied
 * at the <b>actual</b> conversion, plus an efficiency-variance leg to/from 5100
 * Production Variance for {@code actual − standard}. Paired with the material
 * charge (on {@code inventory.RawMaterialsReserved}) and the FG receipt (on
 * {@link WorkOrderManufacturingCompleted}, credited at the full standard cost =
 * material + standard conversion), WIP nets to zero per work order, with the
 * efficiency variance landing in 5100 (dev-todo §2.42 slices C + D).
 *
 * <p>Both amounts are totals for the completed quantity (per-unit × completed
 * quantity), computed by manufacturing from work-centre rates:
 * <ul>
 *   <li>{@code standardConversionCost} — from the product's active routing's
 *       planned minutes (the same value baked into the standard cost, so the
 *       FG receipt and this leg reconcile).</li>
 *   <li>{@code actualConversionCost} — from the work order's operations' actual
 *       minutes. Minutes are per-unit (the routing basis), so an operator
 *       logging actual = planned yields zero variance. Efficiency variance only
 *       (Northwood logs no actual wage rate).</li>
 * </ul>
 * Emitted only when at least one is positive; a SKU with no routing / no rates
 * produces no event.
 */
public record WorkOrderConversionApplied(
    UUID eventId,
    UUID aggregateId,          // work_order_id
    String workOrderNumber,
    UUID finishedProductId,
    BigDecimal actualConversionCost,
    BigDecimal standardConversionCost,
    String currencyCode,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderConversionApplied";

    @Override public String eventType() { return EVENT_TYPE; }
}
