package com.northwood.testharness.inmemory.purchasing;

import com.northwood.product.domain.ReplenishmentStrategy;
import com.northwood.purchasing.application.ToOrderProductLookup;
import com.northwood.purchasing.application.inbox.ReplenishmentStrategyProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link ToOrderProductLookup} that doubles as the matching
 * {@link ReplenishmentStrategyProjection} write-side — read and write surfaces of
 * purchasing's replenishment-strategy fact share one map so seeding via either
 * entry point is consistent (mirrors {@link InMemoryPurchasableProductLookup}).
 *
 * <p>Fail-open like the Jdbc lookup: a product with no recorded strategy reads as
 * not-to-order, so existing tests that don't model strategy keep passing. Only a
 * product explicitly marked {@code to_order} is rejected on the manual path.
 */
public final class InMemoryToOrderProductLookup
    implements ToOrderProductLookup, ReplenishmentStrategyProjection {

    private final Map<UUID, String> strategy = new HashMap<>();

    @Override
    public boolean isToOrder(UUID productId) {
        return ReplenishmentStrategy.TO_ORDER.code().equals(strategy.get(productId));
    }

    @Override
    public void applyReplenishmentStrategy(UUID productId, String replenishmentStrategy) {
        strategy.put(productId, replenishmentStrategy);
    }

    /** Test helper for scenarios that don't run through the inbox handler. */
    public InMemoryToOrderProductLookup put(UUID productId, String replenishmentStrategy) {
        strategy.put(productId, replenishmentStrategy);
        return this;
    }
}
