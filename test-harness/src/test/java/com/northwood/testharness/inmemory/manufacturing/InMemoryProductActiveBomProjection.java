package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.application.inbox.ProductActiveBomProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link ProductActiveBomProjection}: pure latest-wins map of
 * {@code productId → activeBomId}.
 */
public final class InMemoryProductActiveBomProjection implements ProductActiveBomProjection {

    private final Map<UUID, UUID> byProductId = new HashMap<>();

    @Override
    public void apply(UUID productId, UUID newActiveBomId) {
        byProductId.put(productId, newActiveBomId);
    }

    @Override
    public Optional<UUID> findActiveBomId(UUID productId) {
        return Optional.ofNullable(byProductId.get(productId));
    }
}
