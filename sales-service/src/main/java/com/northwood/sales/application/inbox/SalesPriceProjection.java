package com.northwood.sales.application.inbox;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Populates {@code sales.product_pricing.sales_price} / {@code currency_code}
 * from {@code SalesPriceChanged}. Plain UPDATE — the row is seeded on
 * {@code ProductCreated} by {@link ProductCreatedProjection}, so a zero-rows
 * UPDATE is an anomaly (logged WARN, with an insert-fallback for resilience).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcSalesPriceProjection}.
 */
public interface SalesPriceProjection {

    void applySalesPrice(UUID productId, BigDecimal salesPrice, String currencyCode);
}
