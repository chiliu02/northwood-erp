package com.northwood.manufacturing.infrastructure.persistence;

import com.northwood.manufacturing.application.DiscontinuedProductLookup;
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
        Boolean flag = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM manufacturing.product_replenishment
                WHERE product_id = ? AND discontinued_at IS NOT NULL
            )
            """,
            Boolean.class, productId
        );
        return Boolean.TRUE.equals(flag);
    }
}
