package com.northwood.purchasing.application.inbox;

import java.util.UUID;

/**
 * Maintains the authoritative {@code replenishment_strategy} flag on
 * {@code purchasing.product_card} from {@code product.ReplenishmentStrategyChanged},
 * so {@link com.northwood.purchasing.application.ToOrderProductLookup} reflects a
 * steward's to-stock/to-order reclassification rather than only the day-zero
 * {@code 'to_stock'} default the column seeds. Mirrors sales'
 * {@code ReplenishmentStrategyProjection} — both treat product as the master and
 * project the strategy facet locally (purchasing reads it to reject a manual
 * requisition for a to-order product).
 *
 * <p>Application-side port consumed only by {@code *Handler} classes in this
 * package. JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcReplenishmentStrategyProjection}.
 */
public interface ReplenishmentStrategyProjection {

    void applyReplenishmentStrategy(UUID productId, String replenishmentStrategy);
}
