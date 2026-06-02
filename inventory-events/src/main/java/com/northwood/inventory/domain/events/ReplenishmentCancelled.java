package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A replenishment request was cancelled before it could fulfil — the downstream
 * service couldn't source it (manufacturing has no active BOM, purchasing has no
 * supplier / the SKU is discontinued) or inventory found the SKU unsourceable.
 * Emitted by {@code ReplenishmentRequest.markCancelled}.
 *
 * <p>The failure counterpart of {@link ReplenishmentFulfilled}: it carries the
 * same denormalised {@code productId} + sales-order back-reference
 * ({@code sourceSalesOrderHeaderId} / {@code sourceSalesOrderLineId}, non-null
 * only for {@code reason = sales_order_shortage}) so sales' fulfilment-saga
 * fan-in can route the cancellation to the right saga and flip the order to
 * {@code rejected} — without a join back to
 * {@code inventory.replenishment_request}. Reorder-point / work-order-shortage
 * cancellations carry null back-references (no sales saga to notify; consumed
 * only by reporting).
 *
 * <p>{@code reason} is a human-readable cause for logs / the rejection message;
 * no consumer branches on its exact value today.
 */
public record ReplenishmentCancelled(
    UUID eventId,
    UUID aggregateId,
    UUID productId,
    UUID sourceSalesOrderHeaderId,
    UUID sourceSalesOrderLineId,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.ReplenishmentCancelled";

    @Override public String eventType() { return EVENT_TYPE; }
}
