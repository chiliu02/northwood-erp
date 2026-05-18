package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.inbox.ProductDiscontinuedProjection;
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
        jdbc.update("""
            INSERT INTO purchasing.product_card (product_id, discontinued_at)
            VALUES (?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET discontinued_at = EXCLUDED.discontinued_at
            """,
            productId, Timestamp.from(discontinuedAt)
        );
        log.info("stamped purchasing.product_card for product_id={} (at={})",
            productId, discontinuedAt);
    }
}
