package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * §2.35 Slice E: a replenishment has been fulfilled. Emitted by inventory's
 * close-the-loop handlers when the downstream WO completes (manufactured
 * path) or the linked PO's goods receipt lands (purchased path).
 *
 * <p>{@code aggregateId} is the replenishment_request_id. Reporting's
 * {@code reporting.replenishment_history_view} (Slice F) consumes this to
 * flip the row's status to {@code 'fulfilled'} and stamp {@code fulfilled_at}.
 */
public record ReplenishmentFulfilled(
    UUID eventId,
    UUID aggregateId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.ReplenishmentFulfilled";

    @Override public String eventType() { return EVENT_TYPE; }
}
