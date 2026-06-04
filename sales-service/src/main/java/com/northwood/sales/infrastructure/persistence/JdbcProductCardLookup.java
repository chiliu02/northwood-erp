package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.ProductCardLookup;
import java.sql.Timestamp;
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
    public Optional<CatalogPrice> findByProductId(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT sales_price, currency_code, discontinued_at, planning_time_fence_days
                FROM sales.product_card
                WHERE product_id = ?
                """,
                (rs, n) -> {
                    Timestamp ts = rs.getTimestamp("discontinued_at");
                    return new CatalogPrice(
                        rs.getBigDecimal("sales_price"),
                        rs.getString("currency_code"),
                        ts == null ? null : ts.toInstant(),
                        rs.getInt("planning_time_fence_days")
                    );
                },
                productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
