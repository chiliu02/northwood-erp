package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Sales fulfilment saga finished compensation but at least one order-pegged supply
 * leg <b>could not be withdrawn</b> (an un-compensatable leaf: a purchase order the
 * supplier already received, a work order already consuming material). The order
 * itself was cancelled cleanly — this is the escalation signal that a residue needs
 * manual intervention (open an RMA, post a scrap write-off), emitted when the saga
 * reaches {@code compensation_failed}.
 *
 * <p>The failure counterpart of {@link SalesOrderCompensated}: that fires when every
 * leg was withdrawn and the saga reached {@code compensated}; this fires when the
 * saga reached {@code compensation_failed}. Both leave the order cancelled —
 * {@code compensation_failed} is a <i>business outcome</i> (residue to clear), not a
 * broken saga. {@code aggregateId} is the sales-order-header id.
 */
public record SalesOrderCompensationFailed(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    Instant cancelledAt,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderCompensationFailed";

    @Override public String eventType() { return EVENT_TYPE; }
}
