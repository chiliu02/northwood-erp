package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Cancel command accepted by the sales side. The header is already flipped to
 * {@code 'cancelled'} in the same transaction; this event is the request to
 * inventory to compensate — release the stock reservation.
 *
 * <p>Inventory acks with {@code InventorySalesOrderCancellationApplied}; the
 * sales fulfilment saga waits in {@code compensating} until that ack lands,
 * then transitions to {@code compensated}. (§2.40 retired the manufacturing
 * leg — post-§2.37 no work order is bound to a sales order, so inventory is
 * the sole compensation contract.)
 */
public record SalesOrderCancellationRequested(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    String orderNumber,
    UUID customerId,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderCancellationRequested";

    @Override public String eventType() { return EVENT_TYPE; }
}
