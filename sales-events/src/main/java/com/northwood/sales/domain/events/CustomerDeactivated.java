package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Customer was soft-deleted (status flipped to {@code 'inactive'}). New
 * orders against an inactive customer must be rejected at place-order time
 * — historical orders remain valid (snapshot-only policy; see
 * {@code CustomerNameChanged} doc).
 */
public record CustomerDeactivated(
    UUID eventId,
    UUID aggregateId,
    String reason,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "sales.CustomerDeactivated";

    @Override public String eventType() { return EVENT_TYPE; }
}
