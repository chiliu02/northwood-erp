package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.ReorderPolicyChanged;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Apply a {@code product.ReorderPolicyChanged} fact onto the local
 * {@code inventory.stock_item} read-model row. Inventory does not own a
 * reorder-policy aggregate of its own — the policy is authoritatively held
 * by product master (Shape A); this projection only mirrors the values into
 * inventory's schema so subsequent inventory-side queries don't need a
 * cross-schema join (blocked by per-service {@code search_path} anyway).
 *
 * <p>§2.22 demotion: previously inventory carried a full {@code StockItem}
 * aggregate + {@code StockItemRepository} that this projection delegated
 * through. The aggregate never emitted events and held no inventory-side
 * invariants beyond non-negative guards on the projected columns — so it
 * was structurally a {@code *Projection} wearing aggregate clothes. The
 * interface now matches the sibling pattern ({@link ProductCreatedProjection},
 * {@link ProductDiscontinuedProjection}) — a thin port whose JDBC impl
 * lives in {@code infrastructure/persistence/JdbcStockItemProjection}.
 * Promote back to an aggregate when an inventory-originated stock-fact slice
 * (manual stock-adjustment, stock-take, etc.) creates a legitimate first
 * emitter that needs intent-named mutators + outbox events.
 */
public interface StockItemProjection {

    /**
     * Apply a reorder-policy change. If no {@code stock_item} row exists yet
     * for the product (out-of-order delivery: {@code ReorderPolicyChanged}
     * arrived before {@code ProductCreated}'s stub seed landed), the
     * projection WARNs and no-ops — the inbox redelivery once the seed
     * lands will catch the policy up. Idempotent: re-applying the same
     * values is a debug-logged no-op.
     */
    void applyReorderPolicy(UUID productId, BigDecimal reorderPoint, BigDecimal reorderQuantity);
}
