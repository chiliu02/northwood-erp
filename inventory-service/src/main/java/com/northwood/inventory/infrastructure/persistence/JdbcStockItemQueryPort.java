package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.StockItemQueryPort;
import com.northwood.inventory.application.dto.StockItemView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockItemQueryPort implements StockItemQueryPort {

    // Balances joined from stock_balance, summed across warehouses per product
    // (COALESCE to 0 when a product has no balance row yet). Read-side only.
    private static final String SELECT_SQL = """
        SELECT pc.product_id, pc.product_sku, pc.product_name, pc.product_type,
               pc.base_uom_code, pc.stock_tracking_mode,
               pc.reorder_point, pc.reorder_quantity,
               COALESCE(sb.on_hand, 0)  AS on_hand,
               COALESCE(sb.reserved, 0) AS reserved
          FROM inventory.product_card pc
          LEFT JOIN (
              SELECT product_id,
                     SUM(on_hand_quantity)  AS on_hand,
                     SUM(reserved_quantity) AS reserved
                FROM inventory.stock_balance
               GROUP BY product_id
          ) sb ON sb.product_id = pc.product_id
        """;

    private static final RowMapper<StockItemView> ROW_MAPPER = (rs, n) -> {
        java.math.BigDecimal onHand = rs.getBigDecimal("on_hand");
        java.math.BigDecimal reserved = rs.getBigDecimal("reserved");
        return new StockItemView(
            rs.getObject("product_id", UUID.class),
            rs.getString("product_sku"),
            rs.getString("product_name"),
            rs.getString("product_type"),
            rs.getString("base_uom_code"),
            rs.getString("stock_tracking_mode"),
            rs.getBigDecimal("reorder_point"),
            rs.getBigDecimal("reorder_quantity"),
            onHand,
            reserved,
            onHand.subtract(reserved)
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcStockItemQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StockItemView> findByProductId(UUID productId) {
        return jdbc.query(
            SELECT_SQL + " WHERE pc.product_id = ?",
            ROW_MAPPER, productId
        ).stream().findFirst();
    }

    @Override
    public List<StockItemView> findAll() {
        return jdbc.query(SELECT_SQL + " ORDER BY pc.product_sku", ROW_MAPPER);
    }
}
