package com.northwood.sales.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Wire request to change a sales-order line's ordered quantity. */
public record ChangeOrderLineQuantityRequest(
    @NotNull @Positive BigDecimal orderedQuantity
) {}
