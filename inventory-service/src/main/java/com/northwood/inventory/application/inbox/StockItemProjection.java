package com.northwood.inventory.application.inbox;

import com.northwood.inventory.domain.StockItem;
import com.northwood.inventory.domain.StockItemRepository;
import com.northwood.product.domain.events.ReorderPolicyChanged;
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
     * local stock_item projection. §1F.2: inventory's {@code ProductCreated}
     * consumer ({@link ProductCreatedHandler}) now seeds the stub row at
     * registration, so on the happy path a {@code ReorderPolicyChanged}
     * always finds its target. The remaining race window — out-of-order
     * delivery dropping {@code ReorderPolicyChanged} before
     * {@code ProductCreated} — still ends with a WARN-and-no-op here; the
     * inbox redelivery once the seed lands catches the policy up.
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
            "{} received for unknown product_id={} — projection skipped",
            ReorderPolicyChanged.EVENT_TYPE, productId
        ));
    }
}
