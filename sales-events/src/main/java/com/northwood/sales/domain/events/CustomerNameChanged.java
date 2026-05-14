package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Customer's display name was changed. <b>Snapshotted-reference-data
 * policy:</b> existing sales orders + reporting's
 * {@code sales_order_360_view.customer_name} are intentionally NOT updated
 * by consumers of this event. The name on a placed order is the name at
 * placement time — that's how a real ERP behaves (the audit trail must show
 * what was on the order, not what the customer is called today). If a future
 * use case needs name-rippling, this event already carries the data; just
 * add a reporting handler that updates {@code customer_name} on rows whose
 * {@code customer_id} matches. See {@code design-notes.md} →
 * "Snapshotted reference data" for the full rationale.
 */
public record CustomerNameChanged(
    UUID eventId,
    UUID aggregateId,
    String oldName,
    String newName,
    Instant occurredAt
) implements DomainEvent {
    public static final String EVENT_TYPE = "sales.CustomerNameChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
