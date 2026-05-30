package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.ReorderPolicyLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReorderPolicyLookup implements ReorderPolicyLookup {

    private final JdbcTemplate jdbc;

    public JdbcReorderPolicyLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ReorderPolicy> findByProductId(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                """
                SELECT reorder_point, reorder_quantity
                FROM inventory.product_card
                WHERE product_id = ?
                """,
                (rs, n) -> new ReorderPolicy(
                    rs.getBigDecimal("reorder_point"),
                    rs.getBigDecimal("reorder_quantity")
                ),
                productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
