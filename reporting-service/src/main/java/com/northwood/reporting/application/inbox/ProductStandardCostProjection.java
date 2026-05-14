package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maintains {@code reporting.product_standard_cost} from
 * {@code product.StandardCostChanged} events. The financial-dashboard
 * snapshot's {@code inventory_value} reads this cache (joined with
 * {@code available_to_promise_view.on_hand_quantity}) to compute the
 * as-of-now inventory book value.
 *
 * <p>Mirrors finance's {@code ProductStandardCostProjection} — each service
 * keeps its own copy because per-service {@code search_path} forbids
 * cross-schema reads. No cross-service drift in practice: both projections
 * are fed by the same producer event, and the v3 seed bootstraps day-1
 * coverage identically.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductStandardCostProjection}.
 */
public interface ProductStandardCostProjection {

    void apply(UUID productId, BigDecimal standardCost, String currencyCode);
}
