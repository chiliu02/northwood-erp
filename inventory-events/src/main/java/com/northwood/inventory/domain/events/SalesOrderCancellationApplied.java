package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Inventory's ack to {@code sales.SalesOrderCancellationRequested}: the
 * reservation against this sales order (if any) has been released, with the
 * corresponding {@code stock_balance.reserved_quantity} bumps rolled back.
 *
 * <p>Idempotent against zero reservations: if the cancel arrived before the
 * stock reservation was even created (or the reservation was already
 * released), the ack still fires with {@code reservationsReleased = 0} so the
 * sales fulfilment saga can advance to {@code compensated} without a special
 * case.
 */
public record SalesOrderCancellationApplied(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id (saga finds saga by this)
    int reservationsReleased,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.SalesOrderCancellationApplied";

    @Override public String eventType() { return EVENT_TYPE; }
}
