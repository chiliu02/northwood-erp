package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maintains {@code reporting.product_card} from
 * {@code product.StandardCostChanged} events (reporting's consumer-side
 * denormalized record per Product — see {@code docs/conventions.md} →
 * *Consumer-side denormalized tables*). The financial-dashboard snapshot's
 * {@code inventory_value} reads this cache (joined with
 * {@code available_to_promise_view.on_hand_quantity}) to compute the
 * as-of-now inventory book value.
 *
 * <p>Mirrors finance's {@code ProductCardProjection} — each service
 * keeps its own copy because per-service {@code search_path} forbids
 * cross-schema reads. No cross-service drift in practice: both projections
 * are fed by the same producer event, and the baseline seed bootstraps day-1
 * coverage identically.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductCardProjection}.
 */
public interface ProductCardProjection {

    void apply(UUID productId, BigDecimal standardCost, String currencyCode);
}
