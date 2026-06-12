package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.ProductType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Write port for inventory's consolidated {@code inventory.product_card}
 * projection — inventory's consumer-side denormalized record per Product (the
 * {@code _card} convention, {@code docs/conventions.md} → *Consumer-side
 * denormalized tables*). The former {@code stock_item}
 * (sku/name/type/uom/tracking + reorder policy) and {@code product_card}
 * (make-vs-buy flags) are merged into one row whose lifetime mirrors the
 * Product aggregate's: seeded on {@code product.ProductCreated}, maintained
 * by attribute-change events, stamped on {@code product.ProductDiscontinued}.
 *
 * <p>Inventory holds no invariants over any column; every value is projected
 * from upstream product-master events. Pure upsert on write — the inbox dedupes
 * redeliveries and partition keys preserve per-product event order on the bus,
 * so latest-wins is naturally correct (no version column).
 *
 * <p>Application-side port consumed only by {@code *Handler} classes in this
 * package. Non-handler readers go through
 * {@link com.northwood.inventory.application.ProductCardLookup} (make-vs-buy
 * routing), {@link com.northwood.inventory.application.ReorderPolicyLookup}
 * (reorder policy) or {@link com.northwood.inventory.application.StockItemQueryPort}
 * (the stock-items UI). JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCardProjection}.
 */
public interface ProductCardProjection {

    /**
     * Seed a stub row at product registration from {@code product.ProductCreated}.
     * Carries the descriptive columns (sku/name/type) and derives the make-vs-buy
     * defaults from the product type (via {@link #defaultsFor}) so the
     * detection service has non-empty flags for day-zero SKUs before any
     * {@code MakeVsBuyChanged} arrives. Base UOM defaults to {@code 'EA'}
     * (ProductCreated doesn't carry it); tracking-mode + reorder default at the
     * schema level.
     *
     * <p>Insert-only — an out-of-order {@code MakeVsBuyChanged} /
     * {@code ProductDiscontinued} that arrived first wins; the seed is a
     * default, not an authority. JDBC uses {@code ON CONFLICT DO NOTHING}.
     */
    void applyCreated(UUID productId, String sku, String name, String productType);

    void applyMakeVsBuy(UUID productId, boolean isPurchased, boolean isManufactured);

    /**
     * Apply a reorder-policy change. If no {@code product_card} row exists yet
     * for the product (out-of-order delivery: {@code ReorderPolicyChanged}
     * arrived before {@code ProductCreated}'s seed landed), the projection WARNs
     * and no-ops — the inbox redelivery once the seed lands catches the policy
     * up. Idempotent: re-applying the same values is a debug-logged no-op.
     */
    void applyReorderPolicy(UUID productId, BigDecimal reorderPoint, BigDecimal reorderQuantity);

    /**
     * Retire the product. Flips both make-vs-buy flags to {@code false} so the
     * The detection service classifies the SKU as unsourceable (logs + skips)
     * rather than dispatching a replenishment, and stamps {@code discontinued_at}
     * as the authoritative retirement signal — flags-pair {@code (false, false)}
     * is ambiguous with a never-classified row, so consumers that need to
     * distinguish "discontinued" from "unclassified" read this column.
     */
    void applyDiscontinued(UUID productId, Instant discontinuedAt);

    /**
     * Default make-vs-buy flags derived from a product type:
     * <ul>
     *   <li>{@link ProductType#RAW_MATERIAL}, {@link ProductType#SERVICE}
     *       — buy-only ({@code purchased=true}, {@code manufactured=false}).</li>
     *   <li>{@link ProductType#FINISHED_GOOD},
     *       {@link ProductType#SEMI_FINISHED_GOOD} — make-only
     *       ({@code purchased=false}, {@code manufactured=true}).</li>
     *   <li>Null / unknown — both flags true (sourceable either way; user
     *       can refine via {@link #applyMakeVsBuy}).</li>
     * </ul>
     *
     * <p>Same mapping as
     * {@link com.northwood.manufacturing.application.inbox.ProductReplenishmentProjection#defaultsFor}.
     * Duplicated rather than shared because cross-service domain imports are
     * forbidden (schema-per-service rule).
     */
    static MakeVsBuy defaultsFor(String productType) {
        if (ProductType.RAW_MATERIAL.code().equals(productType)
            || ProductType.SERVICE.code().equals(productType)) {
            return new MakeVsBuy(true, false);
        }
        if (ProductType.FINISHED_GOOD.code().equals(productType)
            || ProductType.SEMI_FINISHED_GOOD.code().equals(productType)) {
            return new MakeVsBuy(false, true);
        }
        return new MakeVsBuy(true, true);
    }

    record MakeVsBuy(boolean isPurchased, boolean isManufactured) {}
}
