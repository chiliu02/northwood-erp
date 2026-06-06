package com.northwood.sales.application.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Add a line to an existing sales order. {@code unitPrice} is optional — when
 * null it is resolved from the catalog the same way placement does; when
 * supplied it is a negotiated override (currency-checked against the catalog).
 * {@code expectedVersion} is the optimistic-concurrency token (the order
 * {@code version} the caller last saw, from an {@code If-Match} header); null
 * skips the staleness check.
 */
public record AddOrderLineCommand(
    UUID salesOrderHeaderId,
    Long expectedVersion,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal orderedQuantity,
    BigDecimal unitPrice,
    BigDecimal taxRate
) {}
