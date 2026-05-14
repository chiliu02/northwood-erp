package com.northwood.manufacturing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * §2.8 Slice C: read model for {@code GET /api/products/{id}/materials-cost}.
 * {@code materialsCost} and {@code currencyCode} are nullable together —
 * the UI renders "—" / "n/a" when {@code reason="inputs_missing"}.
 */
public record ProductMaterialsCostResponse(
    UUID productId,
    BigDecimal materialsCost,
    String currencyCode,
    String reason,
    Instant capturedAt
) {}
