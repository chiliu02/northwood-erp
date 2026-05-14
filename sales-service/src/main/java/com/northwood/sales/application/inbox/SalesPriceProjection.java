package com.northwood.sales.application.inbox;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maintains the {@code sales.product_pricing} read projection. Pure upsert —
 * the inbox handler hands over the latest values; this service writes them
 * idempotently. No version column on the projection: the inbox dedupes by
 * {@code event_id} and partition keys preserve per-product event order on
 * the bus, so latest-wins is naturally correct.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcSalesPriceProjection}.
 */
public interface SalesPriceProjection {

    void applySalesPrice(UUID productId, BigDecimal salesPrice, String currencyCode);
}
