package com.northwood.sales.application.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Stamps {@code sales.product_pricing.discontinued_at} for a product that
 * product-service has retired. Plain UPDATE — the row is seeded on
 * {@code ProductCreated} by {@link ProductCreatedProjection}, so a zero-rows
 * UPDATE is an anomaly (logged WARN, with an insert-fallback for resilience).
 *
 * <p>Read side: {@link com.northwood.sales.application.ProductPricingLookup}
 * surfaces the field so {@code SalesOrderService.placeOrder} can reject lines
 * for discontinued products.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductDiscontinuedProjection}.
 */
public interface ProductDiscontinuedProjection {

    void applyDiscontinued(UUID productId, Instant discontinuedAt);
}
