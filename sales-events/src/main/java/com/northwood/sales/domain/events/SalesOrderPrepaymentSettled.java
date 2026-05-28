package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * §2.31 Slice C. The sales fulfilment saga emits this when
 * {@code applyCustomerPaymentReceived} transitions a prepayment-terms order
 * to {@code prepaid} (full settlement of the prepayment invoice). Inventory
 * consumes it to flip {@code sales_order_line_facts.prepayment_settled} to
 * true — the shipment-gate check in {@code ShipmentService.post} reads that
 * column to refuse unpaid prepayment orders with HTTP 409.
 *
 * <p>Header-level signal — no per-line payload. The settled flag applies to
 * every line on the order; inventory updates them all in one UPDATE.
 *
 * <p>{@code aggregateId} is the sales-order-header id.
 */
public record SalesOrderPrepaymentSettled(
    UUID eventId,
    UUID aggregateId,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderPrepaymentSettled";

    @Override public String eventType() { return EVENT_TYPE; }
}
