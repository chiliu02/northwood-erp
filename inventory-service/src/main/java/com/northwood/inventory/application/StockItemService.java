package com.northwood.inventory.application;

import com.northwood.inventory.application.dto.StockItemView;
import com.northwood.inventory.domain.StockItem;
import com.northwood.inventory.domain.StockItemId;
import com.northwood.inventory.domain.StockItemRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for the {@link StockItem} aggregate. Reads only today —
 * stock-item creation/mutation flows through the inbox-driven projection
 * (product master is the source of truth) and the {@code StockItem} aggregate's
 * {@code applyReorderPolicy}, which lands via its own handler path. The
 * controller depends on this service rather than reaching into
 * {@link StockItemRepository} so the application layer stays the single seam
 * between API and domain.
 *
 * <p>Read methods return {@link StockItemView} (in {@code application/dto/})
 * rather than the {@code StockItem} aggregate so {@code api/} never imports
 * a domain type. See CLAUDE.md "Controllers (api/) must depend only on
 * application/".
 */
@Service
public class StockItemService {

    private final StockItemRepository stockItems;

    public StockItemService(StockItemRepository stockItems) {
        this.stockItems = stockItems;
    }

    @Transactional(readOnly = true)
    public Optional<StockItemView> findById(UUID id) {
        return stockItems.findById(StockItemId.of(id)).map(StockItemView::from);
    }

    @Transactional(readOnly = true)
    public Optional<StockItemView> findByProductId(UUID productId) {
        return stockItems.findByProductId(productId).map(StockItemView::from);
    }

    @Transactional(readOnly = true)
    public List<StockItemView> findAll() {
        return stockItems.findAll().stream().map(StockItemView::from).toList();
    }
}
