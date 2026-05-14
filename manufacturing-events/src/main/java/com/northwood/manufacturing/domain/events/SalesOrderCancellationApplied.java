package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Manufacturing's ack to {@code sales.SalesOrderCancellationRequested}: any
 * active work orders for this sales order have been cancelled (status flipped
 * to {@code 'cancelled'}, associated make-to-order saga(s) flipped to
 * {@code 'compensated'}). Always emitted, even if zero WOs were cancelled —
 * the sales fulfilment saga waits on this ack regardless to advance from
 * {@code compensating} to {@code compensated}.
 */
public record SalesOrderCancellationApplied(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id (so saga can find by aggregate)
    int workOrdersCancelled,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.SalesOrderCancellationApplied";

    @Override public String eventType() { return EVENT_TYPE; }
}
