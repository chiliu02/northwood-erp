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
    BigDecimal reorderPoint,
    BigDecimal reorderQuantity,
    String valuationClass,
    UUID activeBomId,
    String status,
    long version
) {
    public static ProductView from(Product p) {
        return new ProductView(
            p.id().value(),
            p.sku().value(),
            p.name(),
            p.description(),
            p.productType().dbValue(),
            p.salesPrice().amount(),
            p.standardCost().amount(),
            p.reorderPoint(),
            p.reorderQuantity(),
            p.valuationClass(),
            p.activeBomId(),
            p.status().name().toLowerCase(),
            p.version()
        );
    }
}
