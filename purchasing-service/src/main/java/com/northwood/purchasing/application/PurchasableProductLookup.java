package com.northwood.purchasing.application;

import java.util.UUID;

/**
 * Narrow operational read over {@code purchasing.product_card.is_purchased}.
 * Used by {@code PurchaseRequisitionService} to reject a requisition line for a
 * make-only SKU — one that no supplier sells us, so it can never become a valid
 * purchase order. The twin of {@link DiscontinuedProductLookup}: both reject new
 * commitments to ineligible products at requisition time.
 *
 * <p>Single-method shape → {@code *Lookup} per the project's port-naming
 * vocabulary.
 */
public interface PurchasableProductLookup {

    /**
     * {@code true} unless purchasing positively knows the SKU is make-only
     * ({@code product_card.is_purchased = false}). A missing card row reads as
     * purchasable — fail-open, matching {@link DiscontinuedProductLookup} on the
     * same row so the two gates never disagree about an un-projected product;
     * the only SKUs we reject are those a {@code MakeVsBuyChanged} (or the
     * type-derived create-time default) has flagged make-only.
     */
    boolean isPurchasable(UUID productId);
}
