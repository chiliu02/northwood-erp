package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SalesPriceChanged(
    UUID eventId,
    UUID aggregateId,
    BigDecimal oldSalesPrice,
    BigDecimal newSalesPrice,
    String currencyCode,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "product.SalesPriceChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
