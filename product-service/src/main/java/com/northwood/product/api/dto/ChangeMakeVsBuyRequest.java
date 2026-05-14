package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotNull;

public record ChangeMakeVsBuyRequest(
    @NotNull Boolean isPurchased,
    @NotNull Boolean isManufactured
) {}
