package com.northwood.purchasing.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RequisitionLineRequest(
    @NotNull UUID productId,
    @NotBlank String productSku,
    @NotBlank String productName,
    @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal requestedQuantity,
    LocalDate requiredDate
) {}
