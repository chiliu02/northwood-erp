package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.DiscontinuedProductLookup;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDiscontinuedProductLookup implements DiscontinuedProductLookup {

    private final JdbcTemplate jdbc;

    public JdbcDiscontinuedProductLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isDiscontinued(UUID productId) {
        Integer hits = jdbc.queryForObject(
            "SELECT COUNT(*) FROM purchasing.product_discontinued WHERE product_id = ?",
            Integer.class, productId
        );
        return hits != null && hits > 0;
    }
}
