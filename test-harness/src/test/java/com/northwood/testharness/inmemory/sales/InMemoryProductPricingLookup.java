package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.application.ProductPricingLookup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryProductPricingLookup implements ProductPricingLookup {

    private final Map<UUID, CatalogPrice> byProductId = new HashMap<>();

    public InMemoryProductPricingLookup put(UUID productId, BigDecimal salesPrice, String currencyCode) {
        byProductId.put(productId, new CatalogPrice(salesPrice, currencyCode, null));
        return this;
    }

    public InMemoryProductPricingLookup markDiscontinued(UUID productId, Instant discontinuedAt) {
        CatalogPrice existing = byProductId.get(productId);
        byProductId.put(productId, new CatalogPrice(
            existing == null ? BigDecimal.ZERO : existing.salesPrice(),
            existing == null ? "AUD" : existing.currencyCode(),
            discontinuedAt
        ));
        return this;
    }

    @Override
    public Optional<CatalogPrice> findByProductId(UUID productId) {
        return Optional.ofNullable(byProductId.get(productId));
    }
}
