package com.northwood.inventory.application.dto;

import com.northwood.inventory.domain.StockItem;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-side projection of {@link StockItem} for the wire layer. Application
 * layer's bridge between the aggregate's read shape and {@code api/dto}
 * response records — controllers never see the {@code StockItem} aggregate.
 */
public record StockItemView(
    UUID stockItemId,
    UUID productId,
    String productSku,
    String productName,
    String productType,
    String baseUomCode,
    String trackingMode,
    BigDecimal reorderPoint,
    BigDecimal reorderQuantity,
    long version
) {
    public static StockItemView from(StockItem s) {
        return new StockItemView(
            s.id().value(),
            s.productId(),
            s.productSku(),
            s.productName(),
            s.productType(),
            s.baseUomCode(),
            s.trackingMode().dbValue(),
            s.reorderPoint(),
            s.reorderQuantity(),
            s.version()
        );
    }
}
