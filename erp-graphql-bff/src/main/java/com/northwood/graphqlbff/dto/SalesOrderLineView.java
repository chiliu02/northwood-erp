package com.northwood.graphqlbff.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mirror of sales-service {@code SalesOrderLineView}. Backs the GraphQL
 * {@code SalesOrderLine} type; {@code product} is resolved from
 * {@link #productId()} via a batch loader (the N+1 hotspot the demo highlights).
 */
public record SalesOrderLineView(
    UUID lineId,
    int lineNumber,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal orderedQuantity,
    BigDecimal reservedQuantity,
    BigDecimal shippedQuantity,
    BigDecimal backorderedQuantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String lineStatus
) {}
