package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.ProductPricingLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductPricingLookup implements ProductPricingLookup {

    private final JdbcTemplate jdbc;

    public JdbcProductPricingLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CatalogPrice> findByProductId(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT sales_price, currency_code
                FROM sales.product_pricing
                WHERE product_id = ?
                """,
                (rs, n) -> new CatalogPrice(
                    rs.getBigDecimal("sales_price"),
                    rs.getString("currency_code")
                ),
                productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
