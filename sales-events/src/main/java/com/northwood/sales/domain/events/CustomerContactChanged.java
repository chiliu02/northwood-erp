package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Customer's email or phone changed. No downstream consumer today —
 * sales orders don't snapshot contact details, so there's nothing to
 * re-project either. Emitted for audit-log + future-consumer parity with
 * the rest of the customer events.
 */
public record CustomerContactChanged(
    UUID eventId,
    UUID aggregateId,
    String email,
    String phone,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "sales.CustomerContactChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
