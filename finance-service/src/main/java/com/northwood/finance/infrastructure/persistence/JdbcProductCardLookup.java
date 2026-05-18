package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.ProductCardLookup;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductCardLookup implements ProductCardLookup {

    private final JdbcTemplate jdbc;

    public JdbcProductCardLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> findStandardCost(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT standard_cost FROM finance.product_card WHERE product_id = ?",
                BigDecimal.class, productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> findValuationClass(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT valuation_class FROM finance.product_card WHERE product_id = ?",
                String.class, productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
