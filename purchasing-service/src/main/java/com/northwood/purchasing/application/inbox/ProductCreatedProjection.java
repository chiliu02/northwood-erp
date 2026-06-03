package com.northwood.purchasing.application.inbox;

import java.util.UUID;

/**
 * Upserts the human-readable product snapshot (sku + name) onto
 * {@code purchasing.product_card} from {@code product.ProductCreated}, so the
 * supplier-price list view can show a SKU/name instead of a raw product UUID.
 * Upsert semantics, sharing the one-row-per-product card with
 * {@link ProductDiscontinuedProjection} (which owns {@code discontinued_at}).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCreatedProjection}.
 */
public interface ProductCreatedProjection {

    void applyCreated(UUID productId, String productSku, String productName);
}
