package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reorder policy facet event. Product master (R&D / planning steward) is the
 * data of record; downstream services (inventory in particular) keep their
 * stock_item.reorder_point / reorder_quantity columns in sync from this event.
 * One event per facet.
 */
public record ReorderPolicyChanged(
    UUID eventId,
    UUID aggregateId,
    BigDecimal oldReorderPoint,
    BigDecimal newReorderPoint,
    BigDecimal oldReorderQuantity,
    BigDecimal newReorderQuantity,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "product.ReorderPolicyChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
