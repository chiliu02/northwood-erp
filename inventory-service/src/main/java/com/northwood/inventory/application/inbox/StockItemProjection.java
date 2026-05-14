package com.northwood.inventory.application.inbox;

import com.northwood.inventory.domain.StockItem;
import com.northwood.inventory.domain.StockItemRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbox-driven projection that maintains inventory's local read-side replica
 * of product master facts ({@code inventory.stock_item}). Today only handles
 * reorder-policy facts; future product-master facets (creation, discontinue)
 * land here as additional methods.
 *
 * <p>Concrete class rather than interface + JDBC adapter: the impl delegates
 * through {@link StockItemRepository} (the aggregate repo) for type-safe
 * mutation, so there's no JdbcTemplate to push down to
 * {@code infrastructure/persistence/} — the standard application/inbox/
 * {@code *Projection} ⇆ infrastructure/persistence/ {@code Jdbc*Projection}
 * split adds no value here.
 */
@Service
public class StockItemProjection {

    private static final Logger log = LoggerFactory.getLogger(StockItemProjection.class);

    private final StockItemRepository stockItems;

    public StockItemProjection(StockItemRepository stockItems) {
        this.stockItems = stockItems;
    }

    /**
     * Apply a {@code ReorderPolicyChanged} fact from product master to the
     * local stock_item projection. If we have no row for the product yet,
     * the projection silently no-ops — when inventory grows a
     * {@code ProductCreated} consumer the row will appear, and a redelivery of
     * the older {@code ReorderPolicyChanged} via the inbox would set it
     * correctly. (Today the seed already has rows for all 5 SKUs.)
     */
    @Transactional
    public void applyReorderPolicy(UUID productId, BigDecimal reorderPoint, BigDecimal reorderQuantity) {
        stockItems.findByProductId(productId).ifPresentOrElse(item -> {
            if (reorderPoint.compareTo(item.reorderPoint()) == 0
                && reorderQuantity.compareTo(item.reorderQuantity()) == 0) {
                log.debug("applyReorderPolicy product_id={} ignored — values unchanged (point={}, qty={})",
                    productId, reorderPoint, reorderQuantity);
                return;
            }
            item.applyReorderPolicy(reorderPoint, reorderQuantity);
            stockItems.save(item);
        }, () -> log.warn(
            "ReorderPolicyChanged received for unknown product_id={} — projection skipped",
            productId
        ));
    }
}
