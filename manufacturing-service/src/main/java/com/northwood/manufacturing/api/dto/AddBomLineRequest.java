package com.northwood.manufacturing.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

public record AddBomLineRequest(
    @NotNull UUID componentProductId,
    @NotBlank String componentSku,
    @NotBlank String componentName,
    @NotNull @Pattern(regexp = "raw|sub_assembly") String componentKind,
    @NotNull @DecimalMin(value = "0.000001", inclusive = true) BigDecimal quantityPerFinishedUnit,
    BigDecimal scrapFactorPercent
) {}
