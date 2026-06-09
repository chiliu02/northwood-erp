package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A new line was added to an existing sales order, post-placement and
 * pre-shipment (the line-amendment flow). Carries the same denormalised line
 * facts as {@link SalesOrderPlaced.PlacedLine} so the consumers that already
 * react to a placed order's lines (inventory reservation, the reporting 360
 * projection) can treat an added line the same way — one more line to reserve.
 *
 * <p>Partition key is {@code aggregateId} (the sales-order header id), the same
 * key {@code SalesOrderPlaced} rides, so a consumer sees the add strictly after
 * the original placement for that order.
 *
 * <p>{@code newOrderTotal} is the order's recomputed header total <i>after</i>
 * the amendment (the aggregate runs {@code recomputeTotals()} before emitting),
 * so the reporting 360 projection can refresh {@code total_amount} /
 * {@code outstanding_amount} without re-reading sales — §1G.3.
 */
public record SalesOrderLineAdded(
    UUID eventId,
    UUID aggregateId,         // sales_order_header_id
    UUID salesOrderLineId,
    int lineNumber,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal orderedQuantity,
    BigDecimal unitPrice,
    BigDecimal newOrderTotal,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderLineAdded";

    @Override public String eventType() { return EVENT_TYPE; }
}
