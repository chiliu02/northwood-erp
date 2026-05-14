package com.northwood.inventory.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ShipmentLineRequest(
    UUID salesOrderLineId,
    @NotNull UUID productId,
    @NotBlank String productSku,
    @NotBlank String productName,
    @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal shippedQuantity,
    BigDecimal unitCost
) {}
