package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Cancel <b>requested</b> on the sales side — phase 1 of the two-phase cancel.
 * The header is <b>not</b> flipped yet; this event asks inventory to arbitrate
 * the cancellation against any concurrent shipment and, if no line has shipped,
 * release the stock reservation.
 *
 * <p>Inventory claims the cancellation on its {@code sales_order_line_facts} rows
 * (refusing a line a shipment already claimed) and, on a win, acks with
 * {@code InventorySalesOrderCancellationApplied}; sales then confirms (header →
 * {@code 'cancelled'}) and the fulfilment saga advances directly to
 * {@code compensated}. If a shipment won the race, inventory sends no ack and the
 * order proceeds as shipped — this is what keeps a concurrent cancel + ship from
 * leaving the order both shipped and cancelled. The manufacturing leg has been
 * retired — no work order is bound to a sales order, so inventory is the sole
 * compensation contract.
 */
public record SalesOrderCancellationRequested(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    String orderNumber,
    UUID customerId,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderCancellationRequested";

    @Override public String eventType() { return EVENT_TYPE; }
}
