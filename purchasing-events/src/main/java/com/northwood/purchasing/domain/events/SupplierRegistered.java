package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A new supplier was onboarded via {@code POST /api/suppliers}. {@code aggregateId}
 * is the supplier id. No cross-service consumer today — other services snapshot
 * supplier name/code onto PO lines + approved-vendor rows — but the event is the
 * navigation anchor for any future supplier directory projection.
 */
public record SupplierRegistered(
    UUID eventId,
    UUID aggregateId,
    String supplierCode,
    String name,
    String email,
    String phone,
    String address,
    String status,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.SupplierRegistered";

    @Override public String eventType() { return EVENT_TYPE; }
}
