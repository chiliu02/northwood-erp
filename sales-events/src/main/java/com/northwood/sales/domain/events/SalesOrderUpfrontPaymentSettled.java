package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * §2.31 Slice C / §2.32 Slice C. The sales fulfilment saga emits this when an
 * order's <b>up-front payment</b> is fully settled before shipment — either a
 * {@code prepayment}-terms order reaching {@code prepaid}, or a {@code deposit}
 * -terms order reaching {@code deposit_paid}. Inventory consumes it to flip
 * {@code sales_order_line_facts.upfront_settled} to true — the shipment-gate
 * check in {@code ShipmentService.post} reads that column to refuse a
 * prepayment/deposit order whose up-front payment hasn't landed (HTTP 409).
 *
 * <p>Header-level signal — no per-line payload. The settled flag applies to
 * every line on the order; inventory updates them all in one UPDATE.
 *
 * <p>{@code aggregateId} is the sales-order-header id.
 */
public record SalesOrderUpfrontPaymentSettled(
    UUID eventId,
    UUID aggregateId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderUpfrontPaymentSettled";

    @Override public String eventType() { return EVENT_TYPE; }
}
