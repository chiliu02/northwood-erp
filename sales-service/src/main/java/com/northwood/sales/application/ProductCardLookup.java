package com.northwood.sales.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port for the {@code sales.product_card} projection (sales's
 * consumer-side denormalized record per Product — see {@code
 * docs/conventions.md} → *Consumer-side denormalized tables*). Used by the
 * order-placement use case to auto-fill {@code unitPrice} from the catalog
 * when callers don't pass one, to validate that the order's currency matches
 * the catalog's currency for the SKU, and to reject lines for products that
 * product-service has discontinued.
 */
public interface ProductCardLookup {

    Optional<CatalogPrice> findByProductId(UUID productId);

    /**
     * Snapshot of a {@code sales.product_card} row. The row is seeded on
     * {@code ProductCreated} with NULL price and NULL currency; both are
     * populated once {@code SalesPriceChanged} fires for the SKU.
     * {@code discontinuedAt} is non-null once {@code ProductDiscontinued}
     * fires.
     *
     * @param salesPrice non-null once SalesPriceChanged has been observed;
     *     NULL while the product is created but never priced (sales treats
     *     this as unsellable, same as discontinued).
     * @param currencyCode non-null iff salesPrice is non-null (the
     *     SalesPriceChanged event always populates both together).
     * @param discontinuedAt non-null when product-service has emitted
     *     {@code ProductDiscontinued} for this product; null otherwise.
     * @param planningTimeFenceDays per-product fence (days), seeded 0 and kept
     *     in sync from {@code product.PlanningTimeFenceChanged}; the fulfilment
     *     saga snapshots it onto the order line at placement so a far-future
     *     order defers its stock reservation until {@code need-by − fence}.
     *     0 = no fence (reserve immediately).
     */
    record CatalogPrice(BigDecimal salesPrice, String currencyCode, Instant discontinuedAt, int planningTimeFenceDays) {}
}
