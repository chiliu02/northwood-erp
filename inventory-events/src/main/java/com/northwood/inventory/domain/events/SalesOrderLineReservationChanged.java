package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Inventory's per-line reply to a sales-order line amendment. Emitted when
 * inventory has incrementally reserved an added line, delta-adjusted a changed
 * line, or released a removed line on an already-reserved order. Lets the
 * fulfilment saga reconcile its outstanding-line set:
 * <ul>
 *   <li>{@code shortageQuantity > 0} — the amended line couldn't be fully
 *       reserved; inventory has raised a {@code ReplenishmentRequest} for it, so
 *       the saga registers the line as outstanding and parks at
 *       {@code stock_reservation_incomplete} until it fulfils;</li>
 *   <li>{@code shortageQuantity == 0} (fully reserved, or released on removal) —
 *       the line is no longer outstanding; the saga drops it from the set and
 *       un-parks to {@code ready_to_ship} once the set empties.</li>
 * </ul>
 *
 * <p>Partition key is {@code aggregateId} = the sales-order header id (the
 * saga's key), the same key its other reservation events ride, so the saga sees
 * amendment replies ordered relative to the original {@code StockReserved}.
 */
public record SalesOrderLineReservationChanged(
    UUID eventId,
    UUID aggregateId,          // sales_order_header_id (saga key + partition key)
    UUID salesOrderLineId,
    UUID productId,
    BigDecimal reservedQuantity,
    BigDecimal shortageQuantity,
    String status,             // StockReservation.Status dbValue: reserved | partially_reserved | failed | released
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.SalesOrderLineReservationChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
