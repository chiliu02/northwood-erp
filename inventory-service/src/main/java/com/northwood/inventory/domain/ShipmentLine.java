package com.northwood.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Value object: one line on a shipment. {@code salesOrderLineId} carries
 * back to the originating sales-order line so finance / sales can match
 * shipment events to the correct line at downstream stages.
 */
public record ShipmentLine(
    UUID id,
    UUID salesOrderLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal shippedQuantity,
    BigDecimal unitCost,
    BigDecimal lineCost
) {}
