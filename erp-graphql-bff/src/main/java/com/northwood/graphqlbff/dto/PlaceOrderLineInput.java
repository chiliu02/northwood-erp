package com.northwood.graphqlbff.dto;

import java.math.BigDecimal;

/**
 * GraphQL {@code PlaceOrderLineInput}. {@code productId} is kept as a
 * {@code String} (the GraphQL {@code ID} scalar): it serialises straight into
 * the sales command's {@code UUID productId} JSON field, avoiding any
 * String→UUID argument-binding conversion on the way in.
 */
public record PlaceOrderLineInput(
    String productId,
    String productSku,
    String productName,
    BigDecimal orderedQuantity,
    BigDecimal unitPrice,
    BigDecimal taxRate
) {}
