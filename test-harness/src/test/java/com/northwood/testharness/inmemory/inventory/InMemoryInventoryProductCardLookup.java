package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.ProductCardLookup;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory double of inventory-side {@link ProductCardLookup} (the §2.35
 * make-vs-buy snapshot inventory owns locally on its {@code product_card},
 * §2.38-consolidated). Defaults to empty — tests seed via {@link #put}, which
 * the §2.35 {@code ReplenishmentDetectionService} reads to route make-vs-buy.
 *
 * <p>Sibling to the manufacturing-side
 * {@code InMemoryProductReplenishmentProjection} in
 * {@code testharness/inmemory/manufacturing/}.
 */
public final class InMemoryInventoryProductCardLookup implements ProductCardLookup {

    private final Map<UUID, Replenishment> rows = new HashMap<>();

    @Override
    public Optional<Replenishment> findByProductId(UUID productId) {
        return Optional.ofNullable(rows.get(productId));
    }

    /** Test helper: seed make-vs-buy flags directly. */
    public InMemoryInventoryProductCardLookup put(UUID productId, boolean isPurchased, boolean isManufactured) {
        rows.put(productId, new Replenishment(isPurchased, isManufactured));
        return this;
    }
}
