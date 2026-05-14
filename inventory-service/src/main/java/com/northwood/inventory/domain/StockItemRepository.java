package com.northwood.inventory.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain-side repository contract for the {@link StockItem} aggregate. The
 * inventory side typically looks up by {@code product_id} (the cross-context
 * link to product master) rather than by {@code stock_item_id}, since events
 * arriving from product carry the product UUID.
 */
public interface StockItemRepository {

    Optional<StockItem> findById(StockItemId id);

    Optional<StockItem> findByProductId(UUID productId);

    /** All projected stock items, ordered by SKU. Used by the demo UI list view. */
    List<StockItem> findAll();

    /**
     * Persist the aggregate. Optimistic concurrency via the {@code version}
     * column is enforced; pending domain events (none for the projection path
     * today) would be written to the outbox in the same transaction.
     */
    void save(StockItem stockItem);
}
