package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.inbox.ReplenishmentStrategyProjection;
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
        // Upsert: a ReplenishmentStrategyChanged may arrive before ProductCreated's
        // seed (out-of-order delivery), so insert a stub row carrying just the
        // strategy; a later ProductCreated fills sku/name and leaves this alone.
        jdbc.update("""
            INSERT INTO purchasing.product_card (product_id, replenishment_strategy)
            VALUES (?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET replenishment_strategy = EXCLUDED.replenishment_strategy
            """,
            productId, replenishmentStrategy
        );
        log.info("upserted purchasing.product_card replenishment_strategy for product_id={} → {}",
            productId, replenishmentStrategy);
    }
}
