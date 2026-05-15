package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory backing for {@link SalesOrderLineFactsProjection}. Map keyed on
 * {@code sales_order_line_id}; redelivery is idempotent (put overwrites with
 * the same value).
 */
public final class InMemorySalesOrderLineFactsProjection implements SalesOrderLineFactsProjection {

    private final Map<UUID, UUID> productIdByLineId = new HashMap<>();

    @Override
    public void applySalesOrderPlaced(UUID salesOrderHeaderId, UUID salesOrderLineId, UUID productId) {
        productIdByLineId.put(salesOrderLineId, productId);
    }

    @Override
    public Optional<UUID> findProductIdForLine(UUID salesOrderLineId) {
        return Optional.ofNullable(productIdByLineId.get(salesOrderLineId));
    }
}
