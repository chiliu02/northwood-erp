package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Customer's billing or shipping address changed. {@code addressType} is
 * either {@code "billing"} or {@code "shipping"} — one event per address
 * change rather than a single combined event so consumers can listen for
 * shipping-only changes without refiltering. Sales orders don't snapshot
 * addresses today (they're resolved at shipment time from the customer
 * master), so a change ripples through to *future* shipments naturally
 * without any reprojection.
 */
public record CustomerAddressChanged(
    UUID eventId,
    UUID aggregateId,
    String addressType,
    String oldAddress,
    String newAddress,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "sales.CustomerAddressChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
