package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.inbox.ProductCardProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory double of inventory-side {@link ProductCardProjection}
 * (the §2.35 Slice A make-vs-buy snapshot inventory owns locally on its
 * {@code product_card}). Defaults to empty — tests seed via {@link #put} or
 * {@link #seedDefaultsFromProductType}.
 *
 * <p>Sibling to the manufacturing-side
 * {@code InMemoryProductReplenishmentProjection} in
 * {@code testharness/inmemory/manufacturing/}.
 */
public final class InMemoryInventoryProductCardProjection
    implements ProductCardProjection {

    private final Map<UUID, Replenishment> rows = new HashMap<>();
    private final Map<UUID, Boolean> discontinued = new HashMap<>();

    @Override
    public void seedDefaultsFromProductType(UUID productId, String productType) {
        Replenishment defaults = ProductCardProjection.defaultsFor(productType);
        rows.putIfAbsent(productId, defaults);
    }

    @Override
    public void applyMakeVsBuy(UUID productId, boolean isPurchased, boolean isManufactured) {
        rows.put(productId, new Replenishment(isPurchased, isManufactured));
    }

    @Override
    public void applyDiscontinued(UUID productId) {
        rows.put(productId, new Replenishment(false, false));
        discontinued.put(productId, true);
    }

    @Override
    public Optional<Replenishment> findByProductId(UUID productId) {
        return Optional.ofNullable(rows.get(productId));
    }

    /** Test helper: seed flags directly. */
    public InMemoryInventoryProductCardProjection put(UUID productId, boolean isPurchased, boolean isManufactured) {
        rows.put(productId, new Replenishment(isPurchased, isManufactured));
        return this;
    }
}
