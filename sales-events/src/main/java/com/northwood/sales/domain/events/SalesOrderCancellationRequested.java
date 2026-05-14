package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Cancel command accepted by the sales side. The header is already flipped to
 * {@code 'cancelled'} in the same transaction; this event is the request to
 * downstream services (inventory, manufacturing) to compensate — release the
 * stock reservation, cancel any in-progress work orders, etc.
 *
 * <p>Inventory and manufacturing each ack with their own
 * {@code SalesOrderCancellationApplied} event; the sales fulfilment saga waits
 * in {@code compensating} until both acks land, then transitions to
 * {@code compensated}.
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
