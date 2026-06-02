package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Set a product's replenishment strategy. Wire-format value
 * ({@code to_stock} | {@code to_order}); parsed and validated against the
 * REQ-PROD-022 invariants in the application/domain layers.
 */
public record ChangeReplenishmentStrategyRequest(
    @NotBlank String replenishmentStrategy
) {}
