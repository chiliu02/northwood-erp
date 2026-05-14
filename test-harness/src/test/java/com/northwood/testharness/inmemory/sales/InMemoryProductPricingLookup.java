package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.application.ProductPricingLookup;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryProductPricingLookup implements ProductPricingLookup {

    private final Map<UUID, CatalogPrice> byProductId = new HashMap<>();

    public InMemoryProductPricingLookup put(UUID productId, BigDecimal salesPrice, String currencyCode) {
        byProductId.put(productId, new CatalogPrice(salesPrice, currencyCode));
        return this;
    }

    @Override
    public Optional<CatalogPrice> findByProductId(UUID productId) {
        return Optional.ofNullable(byProductId.get(productId));
    }
}
