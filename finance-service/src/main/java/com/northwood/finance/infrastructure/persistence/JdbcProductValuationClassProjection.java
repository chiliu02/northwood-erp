package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.inbox.ProductValuationClassProjection;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductValuationClassProjection implements ProductValuationClassProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductValuationClassProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductValuationClassProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findValuationClass(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT valuation_class FROM finance.product_valuation_class WHERE product_id = ?",
                String.class, productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void apply(UUID productId, String valuationClass) {
        jdbc.update("""
            INSERT INTO finance.product_valuation_class (product_id, valuation_class, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (product_id) DO UPDATE SET
                valuation_class = EXCLUDED.valuation_class,
                updated_at = now()
            """,
            productId, valuationClass
        );
        log.info("valuation class projection updated: product={} class={}", productId, valuationClass);
    }
}
