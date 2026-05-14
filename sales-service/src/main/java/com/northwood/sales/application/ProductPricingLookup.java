package com.northwood.sales.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port for the {@code sales.product_pricing} projection. Used by the
 * order-placement use case to auto-fill {@code unitPrice} from the catalog
 * when callers don't pass one, to validate that the order's currency matches
 * the catalog's currency for the SKU, and to reject lines for products that
 * product-service has discontinued.
 */
public interface ProductPricingLookup {

    Optional<CatalogPrice> findByProductId(UUID productId);

    /**
     * Snapshot of a {@code sales.product_pricing} row.
     *
     * @param discontinuedAt non-null when product-service has emitted
     *     {@code ProductDiscontinued} for this product; null otherwise.
     */
    record CatalogPrice(BigDecimal salesPrice, String currencyCode, Instant discontinuedAt) {}
}
