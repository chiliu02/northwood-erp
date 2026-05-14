package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.StockMovementQueryPort;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockMovementQueryPort implements StockMovementQueryPort {

    private final JdbcTemplate jdbc;

    public JdbcStockMovementQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MovementRow> listRecent(int limit) {
        return jdbc.query("""
            SELECT stock_movement_id, warehouse_id, product_id,
                   product_sku, product_name,
                   movement_type, direction,
                   quantity, unit_cost, total_cost,
                   source_type, source_id, movement_date
            FROM inventory.stock_movement
            ORDER BY movement_date DESC, stock_movement_id DESC
            LIMIT ?
            """,
            (rs, n) -> new MovementRow(
                rs.getObject("stock_movement_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getString("product_sku"),
                rs.getString("product_name"),
                rs.getString("movement_type"),
                rs.getString("direction"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("total_cost"),
                rs.getString("source_type"),
                rs.getObject("source_id", UUID.class),
                rs.getTimestamp("movement_date").toInstant()
            ),
            limit
        );
    }
}
