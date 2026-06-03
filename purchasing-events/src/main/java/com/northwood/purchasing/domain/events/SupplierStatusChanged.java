package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A supplier's status changed (active / inactive / blocked) via
 * {@code PATCH /api/suppliers/{id}/status}. {@code aggregateId} is the supplier id.
 */
public record SupplierStatusChanged(
    UUID eventId,
    UUID aggregateId,
    String oldStatus,
    String newStatus,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.SupplierStatusChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
