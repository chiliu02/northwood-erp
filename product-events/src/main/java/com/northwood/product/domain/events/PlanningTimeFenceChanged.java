package com.northwood.product.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Planning time-fence facet event. Product master (planning steward) is the
 * data of record for {@code planning_time_fence_days}; sales keeps its
 * {@code product_card.planning_time_fence_days} column in sync from this event
 * so the fulfilment saga can defer a far-future order's stock reservation until
 * {@code need-by − fence}. One event per facet; days are non-negative
 * (0 = no fence = reserve immediately, today's behaviour).
 */
public record PlanningTimeFenceChanged(
    UUID eventId,
    UUID aggregateId,
    int oldPlanningTimeFenceDays,
    int newPlanningTimeFenceDays,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "product.PlanningTimeFenceChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
