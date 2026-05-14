package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record ChangeStandardCostRequest(
    @NotNull @PositiveOrZero BigDecimal standardCost,
    @NotBlank String currencyCode
) {}
