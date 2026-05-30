package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.ProductCardLookup;
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
    public Optional<Replenishment> findByProductId(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT is_purchased, is_manufactured FROM inventory.product_card WHERE product_id = ?",
                (rs, n) -> new Replenishment(rs.getBoolean("is_purchased"), rs.getBoolean("is_manufactured")),
                productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
