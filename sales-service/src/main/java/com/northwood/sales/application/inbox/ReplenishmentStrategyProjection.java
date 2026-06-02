package com.northwood.sales.application.inbox;

import java.util.UUID;

/**
 * Populates {@code sales.product_card.replenishment_strategy} from
 * {@code product.ReplenishmentStrategyChanged} (§2.43). The fulfilment saga's
 * reserve step reads it to choose the free-stock path ({@code to_stock}) vs the
 * order-pegged supply path ({@code to_order}). Plain UPDATE — the row is seeded
 * on {@code ProductCreated} by {@link ProductCreatedProjection} (default
 * {@code to_stock}), so a zero-rows UPDATE is an anomaly (logged WARN, with an
 * insert-fallback for resilience, mirroring {@link SalesPriceProjection}).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcReplenishmentStrategyProjection}.
 */
public interface ReplenishmentStrategyProjection {

    void applyReplenishmentStrategy(UUID productId, String replenishmentStrategy);
}
