package com.northwood.sales.application.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * Stamps {@code sales.product_pricing.discontinued_at} for a product that
 * product-service has retired. Upsert semantics — if no pricing row exists yet
 * (a discontinue can race ahead of {@code SalesPriceChanged}'s seed), the
 * insert creates a stub row with {@code sales_price = 0} / {@code currency_code = 'AUD'}
 * just to carry the discontinued stamp; a subsequent price emission for the
 * same product would normally not occur (the source aggregate is retired),
 * so the stub is acceptable as a permanent record.
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
