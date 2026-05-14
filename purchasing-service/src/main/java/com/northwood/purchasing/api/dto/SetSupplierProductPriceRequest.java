package com.northwood.purchasing.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

public record SetSupplierProductPriceRequest(
    @NotNull UUID supplierId,
    @NotNull UUID productId,
    @Pattern(regexp = "[A-Z]{3}") String currencyCode,
    @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitPrice
) {}
