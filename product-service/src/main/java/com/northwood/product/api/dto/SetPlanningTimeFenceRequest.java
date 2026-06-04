package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SetPlanningTimeFenceRequest(
    @NotNull @PositiveOrZero Integer planningTimeFenceDays
) {}
