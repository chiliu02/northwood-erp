package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.DiscontinuedProductLookup;
import com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link ProductReplenishmentProjection}. Seed defaults come from
 * the shared {@link ProductReplenishmentProjection#defaultsFor} mapping;
 * {@code seed} is insert-only ({@code putIfAbsent}) to mirror the JDBC
 * adapter's {@code ON CONFLICT DO NOTHING}, while {@code apply} upserts.
 *
 * <p>Also implements {@link DiscontinuedProductLookup} so test wiring needs
 * only one fake — the JDBC side splits these across two adapters because
 * they have different lifetimes, but in-memory they share state via the
 * {@code discontinued} set updated by {@link #applyDiscontinued}.
 */
public final class InMemoryProductReplenishmentProjection
    implements ProductReplenishmentProjection, DiscontinuedProductLookup {

    private final Map<UUID, Replenishment> byProductId = new HashMap<>();
    private final Set<UUID> discontinued = new HashSet<>();

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
        discontinued.add(productId);
    }

    @Override
    public Optional<Replenishment> findByProductId(UUID productId) {
        return Optional.ofNullable(byProductId.get(productId));
    }

    @Override
    public boolean isDiscontinued(UUID productId) {
        return discontinued.contains(productId);
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
