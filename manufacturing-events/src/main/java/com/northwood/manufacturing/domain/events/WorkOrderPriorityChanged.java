package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * §3.5: a work order's priority has changed. Pure CQRS read-side concern —
 * the WO aggregate doesn't track priority (no manufacturing decision flow
 * depends on it today), so the event is emitted directly by the service
 * without an aggregate state mutation. Reporting's production planning
 * board projects this onto {@code priority}.
 */
public record WorkOrderPriorityChanged(
    UUID eventId,
    UUID aggregateId,         // work_order_id
    String priority,          // 'low' | 'normal' | 'high' | 'urgent'
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.WorkOrderPriorityChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
