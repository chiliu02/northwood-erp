package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A supplier's editable details (name / email / phone / address) changed via
 * {@code PUT /api/suppliers/{id}}. {@code aggregateId} is the supplier id.
 * {@code supplier_code} is identity and never changes, so it isn't carried.
 */
public record SupplierDetailsChanged(
    UUID eventId,
    UUID aggregateId,
    String name,
    String email,
    String phone,
    String address,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.SupplierDetailsChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
