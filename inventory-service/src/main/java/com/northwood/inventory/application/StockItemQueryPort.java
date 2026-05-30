package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.StockItemView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side query port over {@code inventory.product_card} (§2.38 — the
 * consolidated stock_item + product_card). Returns {@link StockItemView}
 * directly — the table is a snapshot projection of upstream product-master
 * facts, never an aggregate, so no domain root sits in between. Keyed by
 * {@code productId} (the card's primary key).
 *
 * <p>Lives in {@code application/} per the {@code *QueryPort} convention.
 * JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcStockItemQueryPort}.
 */
public interface StockItemQueryPort {

    Optional<StockItemView> findByProductId(UUID productId);

    /** All projected stock items, ordered by SKU. Used by the demo UI list view. */
    List<StockItemView> findAll();
}
