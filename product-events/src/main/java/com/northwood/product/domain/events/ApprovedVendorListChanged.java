package com.northwood.product.domain.events;

import com.northwood.product.domain.ApprovedVendor;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The approved-vendor list for a SKU changed — engineering / quality has
 * decided which suppliers may supply this product. Drives purchasing's
 * supplier selection at PR→PO conversion: only suppliers on the list
 * are eligible.
 *
 * <p>Carries the new list in full (not a delta) so consumers projecting
 * the read model can replace their copy in one statement. The list element
 * is the domain VO {@link ApprovedVendor}, kept in {@code domain/} so
 * repository ports + adapters don't need to import this event class.
 */
public record ApprovedVendorListChanged(
    UUID eventId,
    UUID aggregateId,
    List<ApprovedVendor> approvedVendors,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "product.ApprovedVendorListChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
