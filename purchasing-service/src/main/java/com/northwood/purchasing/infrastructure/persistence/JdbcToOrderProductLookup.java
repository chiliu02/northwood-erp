package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.product.domain.ReplenishmentStrategy;
import com.northwood.purchasing.application.ToOrderProductLookup;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToOrderProductLookup implements ToOrderProductLookup {

    private static final Logger log = LoggerFactory.getLogger(JdbcToOrderProductLookup.class);

    private final JdbcTemplate jdbc;

    public JdbcToOrderProductLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isToOrder(UUID productId) {
        // Returns null when no card row exists yet → treated as not-to-order
        // (fail-open, see ToOrderProductLookup). Only a row that positively says
        // replenishment_strategy = 'to_order' rejects the manual line.
        String strategy = jdbc.query(
            "SELECT replenishment_strategy FROM purchasing.product_card WHERE product_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            productId
        );
        if (strategy == null) {
            log.debug("no purchasing.product_card row for product_id={} — treating as not-to-order (fail-open)",
                productId);
            return false;
        }
        return ReplenishmentStrategy.TO_ORDER.code().equals(strategy);
    }
}
