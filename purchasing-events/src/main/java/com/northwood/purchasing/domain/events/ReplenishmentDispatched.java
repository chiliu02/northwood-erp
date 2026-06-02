package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted alongside {@link PurchaseRequisitionCreated} when
 * purchasing's {@code ReplenishmentRequestedHandler} creates a PR in response
 * to an {@code inventory.ReplenishmentRequested} event with
 * {@code targetService = "purchasing"}.
 *
 * <p>Carries the {@code replenishmentRequestId} so inventory's
 * close-the-loop handler can flip the originating
 * {@code ReplenishmentRequest} to {@code dispatched} and remember which PR
 * will fulfil it. Inventory then bridges PR → PO via the existing
 * {@code purchasing.PurchaseOrderCreated} so the eventual
 * {@code inventory.GoodsReceived} can resolve back to the replenishment.
 *
 * <p>{@code aggregateId} is the purchase-requisition id — same partition key
 * as the {@code PurchaseRequisitionCreated} emitted in the same transaction,
 * so cross-event ordering on the bus is guaranteed per PR.
 *
 * <p>Sibling-by-name to {@code manufacturing.ReplenishmentDispatched};
 * the two events carry distinct {@code EVENT_TYPE} strings and
 * aggregate semantics — inventory consumes both through dedicated handlers.
 */
public record ReplenishmentDispatched(
    UUID eventId,
    UUID aggregateId,
    UUID replenishmentRequestId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.ReplenishmentDispatched";

    @Override public String eventType() { return EVENT_TYPE; }
}
