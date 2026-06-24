package com.northwood.graphqlbff.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirror of inventory-service {@code StockItemView} (GET /api/stock-items).
 * Backs the GraphQL {@code StockItem} type, indexed by {@link #productId()}.
 */
public record StockItemView(
    UUID productId,
    String productSku,
    String productName,
    String productType,
    String baseUomCode,
    String trackingMode,
    BigDecimal reorderPoint,
    BigDecimal reorderQuantity,
    BigDecimal onHand,
    BigDecimal reserved,
    BigDecimal available
) {}
