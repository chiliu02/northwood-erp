package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link ProductReplenishmentProjection}. Seed defaults come from
 * the shared {@link ProductReplenishmentProjection#defaultsFor} mapping;
 * {@code seed} is insert-only ({@code putIfAbsent}) to mirror the JDBC
 * adapter's {@code ON CONFLICT DO NOTHING}, while {@code apply} upserts.
 */
public final class InMemoryProductReplenishmentProjection implements ProductReplenishmentProjection {

    private final Map<UUID, Replenishment> byProductId = new HashMap<>();

    @Override
    public void seedDefaultsFromProductType(UUID productId, String productType) {
        byProductId.putIfAbsent(productId, ProductReplenishmentProjection.defaultsFor(productType));
    }

    @Override
    public void applyMakeVsBuy(UUID productId, boolean isPurchased, boolean isManufactured) {
        byProductId.put(productId, new Replenishment(isPurchased, isManufactured));
    }

    @Override
    public void applyDiscontinued(UUID productId) {
        byProductId.put(productId, new Replenishment(false, false));
    }

    @Override
    public Optional<Replenishment> findByProductId(UUID productId) {
        return Optional.ofNullable(byProductId.get(productId));
    }

    /**
     * Test-side: directly seed make-only / buy-only / both. Use when the
     * scenario doesn't exercise {@code seedDefaultsFromProductType}.
     */
    public InMemoryProductReplenishmentProjection put(UUID productId, boolean purchased, boolean manufactured) {
        byProductId.put(productId, new Replenishment(purchased, manufactured));
        return this;
    }
}
