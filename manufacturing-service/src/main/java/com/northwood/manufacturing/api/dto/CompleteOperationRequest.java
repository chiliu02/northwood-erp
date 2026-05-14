package com.northwood.manufacturing.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CompleteOperationRequest(
    @NotNull @PositiveOrZero BigDecimal actualMinutes
) {
}
