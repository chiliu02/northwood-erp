package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record ChangeSalesPriceRequest(
    @NotNull @PositiveOrZero BigDecimal salesPrice,
    @NotBlank String currencyCode
) {}
