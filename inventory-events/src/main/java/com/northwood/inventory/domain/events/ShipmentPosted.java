package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Goods have shipped against a sales order. Carries the per-line shipped
 * quantities so:
 *
 * <ul>
 *   <li>Sales advances the fulfilment saga {@code ready_to_ship → goods_shipped}
 *       and emits a richer {@code SalesOrderShipped} event with line pricing
 *       (which inventory doesn't have).</li>
 *   <li>Finance projects the shipment for visibility (no direct invoice
 *       creation here — finance auto-creates the customer invoice from
 *       sales' {@code SalesOrderShipped}, since pricing is sales' domain).</li>
 * </ul>
 *
 * <p>{@code aggregateId} is the shipment id.
 */
public record ShipmentPosted(
    UUID eventId,
    UUID aggregateId,
    String shipmentNumber,
    UUID salesOrderHeaderId,
    UUID customerId,
    String customerName,
    UUID warehouseId,
    String warehouseCode,
    List<ShippedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.ShipmentPosted";

    @Override public String eventType() { return EVENT_TYPE; }

    public record ShippedLine(
        UUID shipmentLineId,
        UUID salesOrderLineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal shippedQuantity,
        BigDecimal unitCost
    ) {}
}
