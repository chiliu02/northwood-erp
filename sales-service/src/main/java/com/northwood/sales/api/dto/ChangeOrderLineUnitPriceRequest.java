package com.northwood.sales.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/** Wire request to override a sales-order line's unit price (sales_manager only). */
public record ChangeOrderLineUnitPriceRequest(
    @NotNull @PositiveOrZero BigDecimal unitPrice
) {}
