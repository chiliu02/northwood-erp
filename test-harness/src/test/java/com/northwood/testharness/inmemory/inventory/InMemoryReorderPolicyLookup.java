package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.ReorderPolicyLookup;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory double of {@link ReorderPolicyLookup} for the test-harness.
 * Defaults to "no policy" for every product — so the §2.35
 * {@code ReplenishmentDetectionService} early-returns without raising a
 * request in tests that haven't opted in. Tests for the §2.35 replenishment
 * loop seed via {@link #put}.
 */
public final class InMemoryReorderPolicyLookup implements ReorderPolicyLookup {

    private final Map<UUID, ReorderPolicy> policies = new HashMap<>();

    public InMemoryReorderPolicyLookup put(UUID productId, BigDecimal reorderPoint, BigDecimal reorderQuantity) {
        policies.put(productId, new ReorderPolicy(reorderPoint, reorderQuantity));
        return this;
    }

    @Override
    public Optional<ReorderPolicy> findByProductId(UUID productId) {
        return Optional.ofNullable(policies.get(productId));
    }
}
