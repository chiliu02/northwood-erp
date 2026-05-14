package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record ProductCreated(
    UUID eventId,
    UUID aggregateId,
    String sku,
    String name,
    String productType,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "product.ProductCreated";

    @Override public String eventType() { return EVENT_TYPE; }
}
