package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.StockBalanceLookup;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcStockBalanceLookup implements StockBalanceLookup {

    private final JdbcTemplate jdbc;

    public JdbcStockBalanceLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal findAvailableQuantity(UUID warehouseId, UUID productId) {
        try {
            BigDecimal available = jdbc.queryForObject(
                """
                SELECT on_hand_quantity - reserved_quantity AS available
                FROM inventory.stock_balance
                WHERE warehouse_id = ? AND product_id = ?
                """,
                BigDecimal.class, warehouseId, productId
            );
            return available == null ? BigDecimal.ZERO : available;
        } catch (EmptyResultDataAccessException e) {
            return BigDecimal.ZERO;
        }
    }
}
