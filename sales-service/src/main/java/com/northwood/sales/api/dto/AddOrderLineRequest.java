package com.northwood.sales.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wire request to add a line to an existing sales order. {@code unitPrice} is
 * optional (null → resolve from the catalog); {@code taxRate} is optional
 * (null → 0). Mirrors {@code PlaceOrderCommand.OrderLine}.
 */
public record AddOrderLineRequest(
    @NotNull UUID productId,
    @NotBlank String productSku,
    @NotBlank String productName,
    @NotNull @Positive BigDecimal orderedQuantity,
    BigDecimal unitPrice,
    BigDecimal taxRate
) {}
