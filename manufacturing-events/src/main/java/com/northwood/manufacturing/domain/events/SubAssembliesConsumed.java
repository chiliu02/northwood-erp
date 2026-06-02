package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A parent work order has completed and consumed its sub-assembly
 * children's outputs from WIP. Inventory consumes this event to decrement
 * {@code wip_balance.on_hand_quantity} for each sub-assembly product —
 * pairing with the WIP bumps that fire when each child WO completes.
 *
 * <p>Emitted exactly once per parent completion (alongside the parent's
 * {@code WorkOrderManufacturingCompleted}). Items list comes from the
 * parent's immediate children (queried by {@code parent_work_order_id}). For
 * a multi-level BOM the inner sub-assembly tier emits its own
 * {@code SubAssembliesConsumed} when it consumes its own children.
 */
public record SubAssembliesConsumed(
    UUID eventId,
    UUID aggregateId,             // parent work_order_id
    List<ConsumedItem> items,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.SubAssembliesConsumed";

    @Override public String eventType() { return EVENT_TYPE; }

    public record ConsumedItem(
        UUID childWorkOrderId,
        UUID productId,
        BigDecimal quantity
    ) {}
}
