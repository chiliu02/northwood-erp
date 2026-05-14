package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A work order has been cancelled mid-fulfilment. Carries
 * {@code parentWorkOrderId} (nullable) so consumers can distinguish top-level
 * cancellations from sub-assembly cancellations, and {@code salesOrderHeaderId}
 * so the originating sales-order context follows the event.
 *
 * <p>Inventory consumes this to release any raw-material reservation tied to
 * {@code workOrderId}; reporting can use it to flip the production-board
 * row to {@code cancelled} (future polish — not wired in this slice).
 */
public record WorkOrderCancelled(
    UUID eventId,
    UUID aggregateId,         // work_order_id
    UUID parentWorkOrderId,   // nullable
    UUID salesOrderHeaderId,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderCancelled";

    @Override public String eventType() { return EVENT_TYPE; }
}
