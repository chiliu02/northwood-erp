package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.inbox.ProductMaterialsCostProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link ProductMaterialsCostProjection}: latest-wins map. The
 * rollup engine writes here; controllers read.
 */
public final class InMemoryProductMaterialsCostProjection implements ProductMaterialsCostProjection {

    private final Map<UUID, MaterialsCost> byProductId = new HashMap<>();

    @Override
    public void apply(
        UUID productId, BigDecimal materialsCost, BigDecimal standardCost,
        String currencyCode, String reason, Instant capturedAt
    ) {
        byProductId.put(productId,
            new MaterialsCost(productId, materialsCost, standardCost, currencyCode, reason, capturedAt));
    }

    @Override
    public Optional<MaterialsCost> findByProductId(UUID productId) {
        return Optional.ofNullable(byProductId.get(productId));
    }

    /** Test-side: seed a starting materialsCost (standardCost defaults to the same value). */
    public InMemoryProductMaterialsCostProjection put(
        UUID productId, BigDecimal cost, String currency, String reason
    ) {
        byProductId.put(productId, new MaterialsCost(productId, cost, cost, currency, reason, Instant.now()));
        return this;
    }
}
