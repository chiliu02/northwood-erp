package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Manufacturing could not release a stock-replenishment work order for the
 * {@code inventory.ReplenishmentRequest} it was dispatched — the product has
 * no active BOM. Inventory consumes this and cancels the
 * originating request ({@code ReplenishmentRequest.markCancelled}, emitting
 * {@code inventory.ReplenishmentCancelled}); for a {@code sales_order_shortage}
 * request that cancellation flips the sales order to {@code rejected}.
 *
 * <p>Failure counterpart of {@link ReplenishmentDispatched}: that event flips
 * the request to {@code dispatched} on a successful WO release; this one
 * triggers cancellation when no BOM exists. No work order is created, so
 * {@code aggregateId} is the originating replenishment-request id — the same
 * partition key the {@code dispatched}/{@code fulfilled}/{@code cancelled}
 * events for that request carry, so cross-event ordering per request holds.
 *
 * <p>Sibling-by-name to {@code purchasing.ReplenishmentUndispatchable} (raised
 * when purchasing has no approved vendor); the two carry distinct
 * {@code EVENT_TYPE} strings and inventory consumes both through dedicated
 * handlers.
 */
public record ReplenishmentUndispatchable(
    UUID eventId,
    UUID aggregateId,
    UUID replenishmentRequestId,
    UUID productId,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.ReplenishmentUndispatchable";

    @Override public String eventType() { return EVENT_TYPE; }
}
