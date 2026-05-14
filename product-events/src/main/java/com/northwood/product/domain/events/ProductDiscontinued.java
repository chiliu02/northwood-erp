package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record ProductDiscontinued(
    UUID eventId,
    UUID aggregateId,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "product.ProductDiscontinued";

    @Override public String eventType() { return EVENT_TYPE; }
}
