package com.northwood.inventory.application.dto;

import com.northwood.inventory.domain.StockAdjustment;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link StockAdjustment} for the wire layer. */
public record StockAdjustmentView(
    UUID id,
    String adjustmentNumber,
    UUID warehouseId,
    String warehouseCode,
    UUID productId,
    String productSku,
    String productName,
    String direction,
    BigDecimal quantity,
    String reason,
    String status,
    long version
) {
    public static StockAdjustmentView from(StockAdjustment a) {
        return new StockAdjustmentView(
            a.id().value(), a.adjustmentNumber(),
            a.warehouseId(), a.warehouseCode(),
            a.productId(), a.productSku(), a.productName(),
            a.direction().code(), a.quantity(), a.reason(),
            a.status().code(), a.version()
        );
    }
}
