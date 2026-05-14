package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StandardCostChanged(
    UUID eventId,
    UUID aggregateId,
    BigDecimal oldStandardCost,
    BigDecimal newStandardCost,
    String currencyCode,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "product.StandardCostChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
