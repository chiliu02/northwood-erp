package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An existing line on a sales order was amended (quantity and/or unit price),
 * post-placement and pre-shipment. The inventory-relevant signal is the
 * quantity delta ({@code newQuantity − previousQuantity}): a consumer that has
 * reserved stock for this line adjusts its reservation by the delta (top-up on
 * an increase, partial release on a decrease). A pure price override carries an
 * unchanged quantity ({@code previousQuantity == newQuantity}) — inventory
 * treats that as a no-op; pricing consumers read the new {@code unitPrice}.
 *
 * <p>Partition key is {@code aggregateId} (the sales-order header id) so the
 * change is ordered relative to the original placement and any prior amendment.
 */
public record SalesOrderLineQuantityChanged(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    UUID salesOrderLineId,
    UUID productId,
    BigDecimal previousQuantity,
    BigDecimal newQuantity,
    BigDecimal unitPrice,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderLineQuantityChanged";

    @Override public String eventType() { return EVENT_TYPE; }
}
