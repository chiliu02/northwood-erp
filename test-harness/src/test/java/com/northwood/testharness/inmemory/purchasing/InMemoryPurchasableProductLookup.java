package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.application.PurchasableProductLookup;
import com.northwood.purchasing.application.inbox.MakeVsBuyChangedProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory {@link PurchasableProductLookup} that doubles as the matching
 * {@link MakeVsBuyChangedProjection} write-side — read and write surfaces of
 * purchasing's make-vs-buy fact share one map so seeding via either entry point
 * is consistent (mirrors {@link InMemoryDiscontinuedProductLookup}).
 *
 * <p>Fail-open like the Jdbc lookup: a product with no recorded flag reads as
 * purchasable, so existing replenishment tests that don't model make-vs-buy keep
 * passing. Only a product explicitly marked make-only is rejected.
 */
public final class InMemoryPurchasableProductLookup
    implements PurchasableProductLookup, MakeVsBuyChangedProjection {

    private final Map<UUID, Boolean> purchased = new HashMap<>();

    @Override
    public boolean isPurchasable(UUID productId) {
        return purchased.getOrDefault(productId, true);
    }

    @Override
    public void applyMakeVsBuy(UUID productId, boolean isPurchased) {
        purchased.put(productId, isPurchased);
    }

    /** Test helper for scenarios that don't run through the inbox handler. */
    public InMemoryPurchasableProductLookup put(UUID productId, boolean isPurchased) {
        purchased.put(productId, isPurchased);
        return this;
    }
}
