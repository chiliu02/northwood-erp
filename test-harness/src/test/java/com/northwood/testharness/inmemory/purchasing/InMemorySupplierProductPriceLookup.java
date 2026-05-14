package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.application.SupplierProductPriceLookup;
import com.northwood.purchasing.domain.SupplierId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class InMemorySupplierProductPriceLookup implements SupplierProductPriceLookup {

    private record Key(UUID supplierId, UUID productId, String currency) {}

    private final Map<Key, BigDecimal> prices = new HashMap<>();

    public InMemorySupplierProductPriceLookup put(UUID supplierId, UUID productId, String currency, BigDecimal price) {
        prices.put(new Key(supplierId, productId, Objects.requireNonNullElse(currency, "AUD")), price);
        return this;
    }

    @Override
    public Optional<BigDecimal> findUnitPrice(SupplierId supplierId, UUID productId, String currencyCode) {
        return Optional.ofNullable(prices.get(new Key(supplierId.value(), productId, currencyCode)));
    }

    @Override
    public Optional<BigDecimal> findUnitPrice(SupplierId supplierId, UUID productId, String currencyCode,
                                              LocalDate at, BigDecimal quantity) {
        return findUnitPrice(supplierId, productId, currencyCode);
    }
}
