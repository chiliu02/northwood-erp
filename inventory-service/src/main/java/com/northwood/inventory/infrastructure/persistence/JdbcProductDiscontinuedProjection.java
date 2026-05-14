package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.ProductDiscontinuedProjection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductDiscontinuedProjection implements ProductDiscontinuedProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductDiscontinuedProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductDiscontinuedProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyDiscontinued(UUID productId, Instant discontinuedAt) {
        int rows = jdbc.update(
            "UPDATE inventory.stock_item SET discontinued_at = ? WHERE product_id = ?",
            Timestamp.from(discontinuedAt), productId
        );
        if (rows == 0) {
            log.warn(
                "ProductDiscontinued received for unknown product_id={} — inventory.stock_item row missing, projection skipped",
                productId
            );
        } else {
            log.info("stamped inventory.stock_item.discontinued_at for product_id={} (at={})",
                productId, discontinuedAt);
        }
    }
}
