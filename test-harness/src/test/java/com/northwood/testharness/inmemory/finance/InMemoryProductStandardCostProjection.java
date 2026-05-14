package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.application.inbox.ProductStandardCostProjection;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryProductStandardCostProjection implements ProductStandardCostProjection {

    private record Row(BigDecimal cost, String currency) {}

    private final Map<UUID, Row> byProductId = new HashMap<>();

    @Override
    public Optional<BigDecimal> findStandardCost(UUID productId) {
        Row r = byProductId.get(productId);
        return Optional.ofNullable(r == null ? null : r.cost());
    }

    @Override
    public void apply(UUID productId, BigDecimal standardCost, String currencyCode) {
        byProductId.put(productId, new Row(standardCost, currencyCode));
    }

    public InMemoryProductStandardCostProjection put(UUID productId, BigDecimal cost, String currency) {
        byProductId.put(productId, new Row(cost, currency));
        return this;
    }
}
