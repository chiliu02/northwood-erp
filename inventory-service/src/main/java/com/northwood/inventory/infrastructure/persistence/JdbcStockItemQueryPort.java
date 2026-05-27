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
        SELECT si.stock_item_id, si.product_id, si.product_sku, si.product_name, si.product_type,
               si.base_uom_code, si.stock_tracking_mode,
               si.reorder_point, si.reorder_quantity, si.version,
               COALESCE(sb.on_hand, 0)  AS on_hand,
               COALESCE(sb.reserved, 0) AS reserved
          FROM inventory.stock_item si
          LEFT JOIN (
              SELECT product_id,
                     SUM(on_hand_quantity)  AS on_hand,
                     SUM(reserved_quantity) AS reserved
                FROM inventory.stock_balance
               GROUP BY product_id
          ) sb ON sb.product_id = si.product_id
        """;

    private static final RowMapper<StockItemView> ROW_MAPPER = (rs, n) -> {
        java.math.BigDecimal onHand = rs.getBigDecimal("on_hand");
        java.math.BigDecimal reserved = rs.getBigDecimal("reserved");
        return new StockItemView(
            rs.getObject("stock_item_id", UUID.class),
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
            onHand.subtract(reserved),
            rs.getLong("version")
        );
    };

    private final JdbcTemplate jdbc;

    public JdbcStockItemQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StockItemView> findById(UUID stockItemId) {
        return jdbc.query(
            SELECT_SQL + " WHERE si.stock_item_id = ?",
            ROW_MAPPER, stockItemId
        ).stream().findFirst();
    }

    @Override
    public Optional<StockItemView> findByProductId(UUID productId) {
        return jdbc.query(
            SELECT_SQL + " WHERE si.product_id = ?",
            ROW_MAPPER, productId
        ).stream().findFirst();
    }

    @Override
    public List<StockItemView> findAll() {
        return jdbc.query(SELECT_SQL + " ORDER BY si.product_sku", ROW_MAPPER);
    }
}
