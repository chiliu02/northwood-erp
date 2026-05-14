package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record SetReorderPolicyRequest(
    @NotNull @PositiveOrZero BigDecimal reorderPoint,
    @NotNull @PositiveOrZero BigDecimal reorderQuantity
) {}
