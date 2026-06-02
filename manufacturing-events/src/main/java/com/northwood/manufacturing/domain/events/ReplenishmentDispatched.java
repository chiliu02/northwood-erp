package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted alongside {@link WorkOrderCreated} when manufacturing's
 * {@code ReplenishmentRequestedHandler} releases a stock work order in
 * response to an {@code inventory.ReplenishmentRequested} event.
 *
 * <p>Carries the {@code replenishmentRequestId} so inventory's
 * close-the-loop handler can flip the originating
 * {@link com.northwood.inventory.domain.ReplenishmentRequest}
 * to {@code dispatched} and remember which WO will fulfil it.
 *
 * <p>{@code aggregateId} is the work-order id — same partition key as the
 * {@code WorkOrderCreated} emitted in the same transaction, so cross-event
 * ordering on the bus is guaranteed per WO.
 *
 * <p>Sibling-by-name to {@code purchasing.ReplenishmentDispatched};
 * the two events carry distinct {@code EVENT_TYPE} strings and aggregate
 * semantics — inventory consumes both through dedicated handlers.
 */
public record ReplenishmentDispatched(
    UUID eventId,
    UUID aggregateId,
    UUID replenishmentRequestId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.ReplenishmentDispatched";

    @Override public String eventType() { return EVENT_TYPE; }
}
