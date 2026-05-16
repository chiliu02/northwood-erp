package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.StockItemView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side query port over {@code inventory.stock_item}. Returns
 * {@link StockItemView} directly — the table is a projection of upstream
 * product-master facts plus inventory-owned tracking-mode, never an
 * aggregate, so no domain root sits in between (see §2.22 demotion).
 *
 * <p>Lives in {@code application/} per the {@code *QueryPort} convention.
 * JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcStockItemQueryPort}.
 */
public interface StockItemQueryPort {

    Optional<StockItemView> findById(UUID stockItemId);

    Optional<StockItemView> findByProductId(UUID productId);

    /** All projected stock items, ordered by SKU. Used by the demo UI list view. */
    List<StockItemView> findAll();
}
