package com.northwood.graphqlbff.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirror of product-service {@code ProductView} (GET /api/products[/{id}]).
 * Backs the GraphQL {@code Product} type; {@code stock} and {@code atp} are
 * resolved from {@link #id()} via inventory / reporting batch loaders. The
 * upstream key is {@code productId}; the schema exposes {@code id}, mapped via
 * {@link JsonProperty}.
 */
public record ProductView(
    @JsonProperty("productId") UUID id,
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
) {}
