package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.application.inbox.ProductValuationClassProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryProductValuationClassProjection implements ProductValuationClassProjection {

    private final Map<UUID, String> byProductId = new HashMap<>();

    @Override
    public Optional<String> findValuationClass(UUID productId) {
        return Optional.ofNullable(byProductId.get(productId));
    }

    @Override
    public void apply(UUID productId, String valuationClass) {
        byProductId.put(productId, valuationClass);
    }

    public InMemoryProductValuationClassProjection put(UUID productId, String valuationClass) {
        byProductId.put(productId, valuationClass);
        return this;
    }
}
