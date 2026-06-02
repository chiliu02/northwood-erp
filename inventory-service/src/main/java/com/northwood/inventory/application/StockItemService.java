package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.StockItemView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service over the {@code inventory.product_card} read-model
 * (the consolidated stock_item + product_card). Read-only — every column is
 * projected from product master (sku, name, type, base UoM, make-vs-buy,
 * reorder policy) or carries a schema default (tracking mode). No
 * inventory-originated write paths exist; the table is a snapshot projection.
 *
 * <p>The controller depends on this service rather than reaching into
 * {@link StockItemQueryPort} so the application layer stays the single
 * seam between API and the rest of the system. View records produced
 * directly by the query port; no domain-to-DTO mapping needed.
 */
@Service
public class StockItemService {

    private final StockItemQueryPort stockItems;

    public StockItemService(StockItemQueryPort stockItems) {
        this.stockItems = stockItems;
    }

    @Transactional(readOnly = true)
    public Optional<StockItemView> findByProductId(UUID productId) {
        return stockItems.findByProductId(productId);
    }

    @Transactional(readOnly = true)
    public List<StockItemView> findAll() {
        return stockItems.findAll();
    }
}
