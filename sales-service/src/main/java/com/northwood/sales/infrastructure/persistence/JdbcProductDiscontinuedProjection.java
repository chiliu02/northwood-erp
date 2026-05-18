package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.inbox.ProductDiscontinuedProjection;
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
        int rows = jdbc.update("""
            UPDATE sales.product_card
               SET discontinued_at = ?
             WHERE product_id = ?
            """,
            java.sql.Timestamp.from(discontinuedAt), productId
        );
        if (rows == 0) {
            log.warn("ProductDiscontinued for product_id={} found no sales.product_card row — "
                + "ProductCreated seed missed or replayed out of order; falling back to insert",
                productId);
            jdbc.update("""
                INSERT INTO sales.product_card (product_id, discontinued_at)
                VALUES (?, ?)
                ON CONFLICT (product_id) DO UPDATE
                    SET discontinued_at = EXCLUDED.discontinued_at
                """,
                productId, java.sql.Timestamp.from(discontinuedAt)
            );
        } else {
            log.info("stamped sales.product_card.discontinued_at for product_id={} (at={})",
                productId, discontinuedAt);
        }
    }
}
