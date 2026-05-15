package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.inbox.ProductCreatedProjection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductCreatedProjection implements ProductCreatedProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductCreatedProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductCreatedProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void apply(UUID productId) {
        int rows = jdbc.update("""
            INSERT INTO sales.product_pricing (product_id, sales_price, currency_code)
            VALUES (?, NULL, NULL)
            ON CONFLICT (product_id) DO NOTHING
            """,
            productId
        );
        if (rows == 0) {
            log.debug("sales.product_pricing row already exists for product_id={} — ProductCreated stub skipped",
                productId);
        } else {
            log.info("created sales.product_pricing stub for product_id={} (NULL price, NULL currency)",
                productId);
        }
    }
}
