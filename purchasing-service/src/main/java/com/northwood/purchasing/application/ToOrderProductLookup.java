package com.northwood.purchasing.application;

import java.util.UUID;

/**
 * Narrow operational read over {@code purchasing.product_card.replenishment_strategy}.
 * Used by {@code PurchaseRequisitionService} to reject a <em>manual</em>
 * requisition line for a to-order SKU. A to-order line never draws from free
 * stock — each sales order raises its own dedicated, order-pegged supply — so
 * stock bought manually would land in free ATP and could never be reserved by a
 * sales order (it would orphan as dead inventory). The system-driven
 * order-pegged buy flow ({@code createForStockReplenishment}) is unaffected: it
 * is the legitimate way a to-order product is purchased.
 *
 * <p>Single-method shape → {@code *Lookup} per the project's port-naming
 * vocabulary; the twin of {@link PurchasableProductLookup}.
 */
public interface ToOrderProductLookup {

    /**
     * {@code true} only when purchasing positively knows the SKU is to-order
     * ({@code product_card.replenishment_strategy = 'to_order'}). A missing card
     * row reads as not-to-order — fail-open, matching
     * {@link PurchasableProductLookup} / {@link DiscontinuedProductLookup} on the
     * same row so the gates never disagree about an un-projected product.
     */
    boolean isToOrder(UUID productId);
}
