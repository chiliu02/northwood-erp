package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Sales fulfilment saga has finished compensation: both downstream services
 * (inventory + manufacturing) have acked their cancel-applied events, and the
 * saga has transitioned to {@code 'compensated'}. Reporting consumes this to
 * flip the {@code sales_order_360_view.order_status} to {@code 'cancelled'}.
 *
 * <p>Distinct from {@code SalesOrderCancellationRequested} (which fires on the
 * cancel command, before downstream services have done their work):
 * {@code SalesOrderCompensated} is the "done" signal.
 */
public record SalesOrderCompensated(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    Instant cancelledAt,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderCompensated";

    @Override public String eventType() { return EVENT_TYPE; }
}
