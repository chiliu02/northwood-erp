package com.northwood.purchasing.application.inbox;

import com.northwood.product.domain.ProductType;
import java.util.UUID;

/**
 * Upserts the human-readable product snapshot (sku + name) and the make-vs-buy
 * default onto {@code purchasing.product_card} from {@code product.ProductCreated},
 * so the supplier-price list view can show a SKU/name instead of a raw product
 * UUID and {@link com.northwood.purchasing.application.PurchasableProductLookup}
 * can reject a make-only SKU on a new requisition. Upsert semantics, sharing the
 * one-row-per-product card with {@link ProductDiscontinuedProjection} (which owns
 * {@code discontinued_at}) and {@link MakeVsBuyChangedProjection} (which owns the
 * authoritative {@code is_purchased} once a steward classifies the SKU).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCreatedProjection}.
 */
public interface ProductCreatedProjection {

    /**
     * Seed sku/name and a day-zero {@code is_purchased} derived from the product
     * type. Insert-only on {@code is_purchased} (the on-conflict path leaves it
     * alone) so an out-of-order {@link MakeVsBuyChangedProjection} that landed
     * first — the authority — isn't overwritten by this default.
     */
    void applyCreated(UUID productId, String productSku, String productName, String productType);

    /**
     * Day-zero make-vs-buy default from a product type, until a
     * {@code product.MakeVsBuyChanged} refines it. Raw materials and services are
     * bought; finished + semi-finished goods are made. Unknown/null defaults to
     * purchasable (sourceable either way — the gate stays permissive when the
     * type is unrecognised). Same mapping as inventory's
     * {@code ProductCardProjection.defaultsFor}, narrowed to the purchased flag;
     * duplicated rather than shared because cross-service domain imports are
     * forbidden (schema-per-service rule).
     */
    static boolean defaultPurchased(String productType) {
        if (ProductType.FINISHED_GOOD.code().equals(productType)
            || ProductType.SEMI_FINISHED_GOOD.code().equals(productType)) {
            return false;
        }
        return true;
    }
}
