package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Purchasing could not raise a stock-replenishment requisition
 * for the {@code inventory.ReplenishmentRequest} it was dispatched — the
 * product has no approved vendor. Inventory consumes this and cancels the
 * originating request ({@code ReplenishmentRequest.markCancelled}, emitting
 * {@code inventory.ReplenishmentCancelled}); for a {@code sales_order_shortage}
 * request that cancellation flips the sales order to {@code rejected}.
 *
 * <p>Failure counterpart of {@link ReplenishmentDispatched}: that event flips
 * the request to {@code dispatched} once a requisition is created; this one
 * triggers cancellation when no vendor can supply the product. No requisition
 * is created, so {@code aggregateId} is the originating replenishment-request
 * id — the same partition key the request's other lifecycle events carry.
 *
 * <p>Sibling-by-name to {@code manufacturing.ReplenishmentUndispatchable}
 * (raised when manufacturing has no active BOM); the two carry distinct
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

    public static final String EVENT_TYPE = "purchasing.ReplenishmentUndispatchable";

    @Override public String eventType() { return EVENT_TYPE; }
}
