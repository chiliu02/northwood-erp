package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A released work order has been cancelled — the order-pegged compensation path
 * (a cancelled {@code to_order} manufactured sales line whose dedicated work order
 * had not yet started production). Emitted by {@code WorkOrder.cancel}, reachable
 * only from {@code released} (nothing physically committed: no operation started, no
 * material consumed). Inventory consumes it to release the work order's reserved raw
 * materials back to the free pool; the cross-service ack to the sales saga is the
 * separate {@link WorkOrderCancellationApplied}.
 *
 * <p>{@code previousStatus} records the status the WO held before cancellation
 * ({@code 'released'}). The sales-order pair is non-null only for an order-pegged
 * WO; a make-to-stock WO carries nulls. {@code aggregateId} is the work-order id.
 */
public record WorkOrderCancelled(
    UUID eventId,
    UUID aggregateId,                 // work_order_id
    String workOrderNumber,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    UUID replenishmentRequestId,
    String previousStatus,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderCancelled";

    @Override public String eventType() { return EVENT_TYPE; }
}
