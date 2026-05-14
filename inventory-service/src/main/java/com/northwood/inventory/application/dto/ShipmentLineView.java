package com.northwood.inventory.application.dto;

import com.northwood.inventory.domain.ShipmentLine;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link ShipmentLine} for the wire layer. */
public record ShipmentLineView(
    UUID lineId,
    UUID salesOrderLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal shippedQuantity,
    BigDecimal unitCost,
    BigDecimal lineCost
) {
    public static ShipmentLineView from(ShipmentLine l) {
        return new ShipmentLineView(
            l.id(), l.salesOrderLineId(),
            l.productId(), l.productSku(), l.productName(),
            l.shippedQuantity(), l.unitCost(), l.lineCost()
        );
    }
}
