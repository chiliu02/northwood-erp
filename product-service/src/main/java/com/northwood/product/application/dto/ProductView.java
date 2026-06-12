package com.northwood.product.application.dto;

import com.northwood.product.domain.Product;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link Product} for the wire layer. */
public record ProductView(
    UUID productId,
    String sku,
    String name,
    String description,
    String productType,
    BigDecimal salesPrice,
    BigDecimal standardCost,
    boolean purchased,
    boolean manufactured,
    BigDecimal reorderPoint,
    BigDecimal reorderQuantity,
    String replenishmentStrategy,
    String valuationClass,
    UUID activeBomId,
    int planningTimeFenceDays,
    String status,
    long version
) {
    public static ProductView from(Product p) {
        return new ProductView(
            p.id().value(),
            p.sku().value(),
            p.name(),
            p.description(),
            p.productType().code(),
            p.salesPrice().amount(),
            p.standardCost().amount(),
            p.isPurchased(),
            p.isManufactured(),
            p.reorderPoint(),
            p.reorderQuantity(),
            p.replenishmentStrategy() == null ? null : p.replenishmentStrategy().code(),
            p.valuationClass() == null ? null : p.valuationClass().code(),
            p.activeBomId(),
            p.planningTimeFenceDays(),
            p.status().code(),
            p.version()
        );
    }
}
