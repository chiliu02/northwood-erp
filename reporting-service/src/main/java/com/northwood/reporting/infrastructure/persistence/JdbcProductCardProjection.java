package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.inbox.ProductCardProjection;
import com.northwood.shared.domain.Currencies;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductCardProjection implements ProductCardProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductCardProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductCardProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void apply(UUID productId, BigDecimal standardCost, String currencyCode) {
        jdbc.update("""
            INSERT INTO reporting.product_card (product_id, standard_cost, currency_code)
            VALUES (?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE SET
                standard_cost = EXCLUDED.standard_cost,
                currency_code = EXCLUDED.currency_code
            """,
            productId, standardCost, currencyCode == null ? Currencies.AUD : currencyCode
        );
        log.debug("reporting product_card updated: product={} cost={} {}", productId, standardCost, currencyCode);
    }
}
