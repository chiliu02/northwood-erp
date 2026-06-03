package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.inbox.PlanningTimeFenceProjection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPlanningTimeFenceProjection implements PlanningTimeFenceProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcPlanningTimeFenceProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcPlanningTimeFenceProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyPlanningTimeFence(UUID productId, int planningTimeFenceDays) {
        int rows = jdbc.update("""
            UPDATE sales.product_card
               SET planning_time_fence_days = ?
             WHERE product_id = ?
            """,
            planningTimeFenceDays, productId
        );
        if (rows == 0) {
            log.warn("PlanningTimeFenceChanged for product_id={} found no sales.product_card row — "
                + "ProductCreated seed missed or replayed out of order; falling back to insert",
                productId);
            jdbc.update("""
                INSERT INTO sales.product_card (product_id, planning_time_fence_days)
                VALUES (?, ?)
                ON CONFLICT (product_id) DO UPDATE
                    SET planning_time_fence_days = EXCLUDED.planning_time_fence_days
                """,
                productId, planningTimeFenceDays
            );
        } else {
            log.info("updated sales.product_card for product_id={} → fence {} day(s)",
                productId, planningTimeFenceDays);
        }
    }
}
