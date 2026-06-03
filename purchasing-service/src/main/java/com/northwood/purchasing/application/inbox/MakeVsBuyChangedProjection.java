package com.northwood.purchasing.application.inbox;

import java.util.UUID;

/**
 * Maintains the authoritative {@code is_purchased} flag on
 * {@code purchasing.product_card} from {@code product.MakeVsBuyChanged}, so
 * {@link com.northwood.purchasing.application.PurchasableProductLookup} reflects
 * a steward's make-vs-buy reclassification rather than only the day-zero default
 * seeded by {@link ProductCreatedProjection}. Purchasing tracks only the
 * purchased facet — {@code is_manufactured} is manufacturing's concern.
 *
 * <p>Application-side port consumed only by {@code *Handler} classes in this
 * package. JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcMakeVsBuyChangedProjection}.
 */
public interface MakeVsBuyChangedProjection {

    void applyMakeVsBuy(UUID productId, boolean isPurchased);
}
