package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The order was <b>rejected</b> — the system-driven absorbing terminal for an order
 * that can never be fulfilled (a short line's replenishment could not be sourced:
 * unsourceable SKU, no active BOM, no approved vendor). The reject counterpart of
 * {@link SalesOrderCompensated}: both are <em>confirmed</em> "order definitively
 * terminated, nothing shipped" facts, distinct from the cancel <em>request</em>
 * ({@link SalesOrderCancellationRequested}).
 *
 * <p>Emitted by {@code SalesOrder.reject} alongside {@code SalesOrderCancellationRequested}
 * (the latter still drives inventory's reservation release). The split exists so a
 * downstream that must act <b>only once the order is definitively non-shippable</b>
 * — finance refunding a paid prepayment/deposit — keys on this confirmed terminal
 * rather than the cancel request, which (post the two-phase cancel) fires before the
 * cancel-vs-ship race is arbitrated. A reject is always safe: a rejected order was
 * never reserved, so it can never ship.
 *
 * <p>{@code aggregateId} is the sales-order-header id.
 */
public record SalesOrderRejected(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    String orderNumber,
    UUID customerId,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderRejected";

    @Override public String eventType() { return EVENT_TYPE; }
}
