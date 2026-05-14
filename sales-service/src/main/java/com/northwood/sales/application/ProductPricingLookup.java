package com.northwood.sales.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port for the {@code sales.product_pricing} projection. Used by the
 * order-placement use case to auto-fill {@code unitPrice} from the catalog
 * when callers don't pass one, and to validate that the order's currency
 * matches the catalog's currency for the SKU.
 */
public interface ProductPricingLookup {

    Optional<CatalogPrice> findByProductId(UUID productId);

    record CatalogPrice(BigDecimal salesPrice, String currencyCode) {}
}
