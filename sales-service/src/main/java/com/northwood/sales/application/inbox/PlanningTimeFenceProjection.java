package com.northwood.sales.application.inbox;

import java.util.UUID;

/**
 * Populates {@code sales.product_card.planning_time_fence_days} from
 * {@code product.PlanningTimeFenceChanged}. Plain UPDATE — the row is seeded on
 * {@code ProductCreated} by {@link ProductCreatedProjection} (fence defaults to
 * 0 via the column default), so a zero-rows UPDATE is an anomaly (logged WARN,
 * with an insert-fallback for resilience).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcPlanningTimeFenceProjection}.
 */
public interface PlanningTimeFenceProjection {

    void applyPlanningTimeFence(UUID productId, int planningTimeFenceDays);
}
