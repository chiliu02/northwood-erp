package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.application.DiscontinuedProductLookup;
import com.northwood.purchasing.application.inbox.ProductDiscontinuedProjection;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory {@link DiscontinuedProductLookup} that doubles as the matching
 * {@link ProductDiscontinuedProjection} write-side — the read and write
 * surfaces of purchasing's discontinued-product fact are kept on the same
 * underlying set in tests so seeding via either entry point is consistent.
 */
public final class InMemoryDiscontinuedProductLookup
    implements DiscontinuedProductLookup, ProductDiscontinuedProjection {

    private final Set<UUID> discontinued = new HashSet<>();

    @Override
    public boolean isDiscontinued(UUID productId) {
        return discontinued.contains(productId);
    }

    @Override
    public void applyDiscontinued(UUID productId, Instant discontinuedAt) {
        discontinued.add(productId);
    }

    /** Test helper for scenarios that don't run through the inbox handler. */
    public InMemoryDiscontinuedProductLookup put(UUID productId) {
        discontinued.add(productId);
        return this;
    }
}
