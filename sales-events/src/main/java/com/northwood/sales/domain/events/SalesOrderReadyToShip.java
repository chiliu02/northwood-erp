package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Sales fulfilment saga has reached {@code ready_to_ship}: every ordered line
 * is either reserved from on-hand stock (the full-reservation shortcut) or has
 * been produced by manufacturing (the last work order finished), so the order
 * can now be shipped. Reporting consumes this to advance
 * {@code sales_order_360_view.order_status} to {@code 'ready_to_ship'} — the
 * value the shipment UI's order picker filters on.
 *
 * <p>Emitted from both inbox handlers that can drive the saga into
 * {@code ready_to_ship}: {@code StockReservedHandler} (full cover, skipping
 * manufacturing) and {@code WorkOrderManufacturingCompletedHandler} (final WO
 * completion). Distinct from {@code SalesOrderShipped}, which fires later when
 * goods physically leave on a posted shipment.
 */
public record SalesOrderReadyToShip(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderReadyToShip";

    @Override public String eventType() { return EVENT_TYPE; }
}
