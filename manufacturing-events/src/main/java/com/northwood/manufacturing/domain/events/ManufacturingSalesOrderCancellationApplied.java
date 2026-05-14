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
 *
 * <p><b>Java name vs wire format.</b> The class is prefixed with
 * {@code Manufacturing} only to disambiguate from inventory's equivalent ack
 * (Java's flat namespace can't carry both as {@code SalesOrderCancellationApplied}
 * without forcing FQNs at every dual-import site). The wire format
 * {@code "manufacturing.SalesOrderCancellationApplied"} is unchanged — the
 * {@code manufacturing.} prefix already conveys the source service, so doubling
 * it in the event name would read awkwardly to consumers.
 */
public record ManufacturingSalesOrderCancellationApplied(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id (so saga can find by aggregate)
    int workOrdersCancelled,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.SalesOrderCancellationApplied";

    @Override public String eventType() { return EVENT_TYPE; }
}
