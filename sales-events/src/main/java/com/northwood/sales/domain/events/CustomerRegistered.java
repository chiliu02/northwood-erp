package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A new customer has been registered in the master. Carries the full
 * starting shape; downstream consumers (none today; reporting could
 * project a customer directory in future) can replace their copy.
 *
 * <p>Note: sales orders snapshot {@code customer_id} + {@code customer_code}
 * + {@code customer_name} at placement time. Subsequent customer renames
 * do not ripple to existing orders or to reporting's
 * {@code sales_order_360_view.customer_name} — that's intentional (audit
 * correctness; the order shows the name at time of placement). See
 * {@code design-notes.md} → "Snapshotted reference data".
 */
public record CustomerRegistered(
    UUID eventId,
    UUID aggregateId,
    String customerCode,
    String name,
    String email,
    String phone,
    String billingAddress,
    String shippingAddress,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "sales.CustomerRegistered";

    @Override public String eventType() { return EVENT_TYPE; }
}
