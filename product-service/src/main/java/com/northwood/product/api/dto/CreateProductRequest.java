package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateProductRequest(
    @NotBlank String sku,
    @NotBlank String name,
    String description,
    @NotBlank String productType,
    @NotNull UUID baseUomId,
    @NotNull @PositiveOrZero BigDecimal salesPrice,
    @NotNull @PositiveOrZero BigDecimal standardCost,
    @NotBlank String currencyCode
) {}
