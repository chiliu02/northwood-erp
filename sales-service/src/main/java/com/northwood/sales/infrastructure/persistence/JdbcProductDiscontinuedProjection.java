package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.inbox.ProductDiscontinuedProjection;
import java.math.BigDecimal;
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
            INSERT INTO sales.product_pricing (product_id, sales_price, currency_code, discontinued_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET discontinued_at = EXCLUDED.discontinued_at
            """,
            productId, BigDecimal.ZERO, "AUD", java.sql.Timestamp.from(discontinuedAt)
        );
        log.info("stamped sales.product_pricing.discontinued_at for product_id={} (at={})",
            productId, discontinuedAt);
    }
}
