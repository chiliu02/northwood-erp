package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.inbox.ReplenishmentStrategyProjection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReplenishmentStrategyProjection implements ReplenishmentStrategyProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcReplenishmentStrategyProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcReplenishmentStrategyProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyReplenishmentStrategy(UUID productId, String replenishmentStrategy) {
        int rows = jdbc.update("""
            UPDATE sales.product_card
               SET replenishment_strategy = ?
             WHERE product_id = ?
            """,
            replenishmentStrategy, productId
        );
        if (rows == 0) {
            log.warn("ReplenishmentStrategyChanged for product_id={} found no sales.product_card row — "
                + "ProductCreated seed missed or replayed out of order; falling back to insert",
                productId);
            jdbc.update("""
                INSERT INTO sales.product_card (product_id, replenishment_strategy)
                VALUES (?, ?)
                ON CONFLICT (product_id) DO UPDATE
                    SET replenishment_strategy = EXCLUDED.replenishment_strategy
                """,
                productId, replenishmentStrategy
            );
        } else {
            log.info("updated sales.product_card for product_id={} → replenishment_strategy={}",
                productId, replenishmentStrategy);
        }
    }
}
