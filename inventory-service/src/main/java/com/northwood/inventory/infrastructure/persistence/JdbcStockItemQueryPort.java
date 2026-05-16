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

    private static final String SELECT_SQL = """
        SELECT stock_item_id, product_id, product_sku, product_name, product_type,
               base_uom_code, stock_tracking_mode,
               reorder_point, reorder_quantity, version
          FROM inventory.stock_item
        """;

    private static final RowMapper<StockItemView> ROW_MAPPER = (rs, n) -> new StockItemView(
        rs.getObject("stock_item_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getString("product_type"),
        rs.getString("base_uom_code"),
        rs.getString("stock_tracking_mode"),
        rs.getBigDecimal("reorder_point"),
        rs.getBigDecimal("reorder_quantity"),
        rs.getLong("version")
    );

    private final JdbcTemplate jdbc;

    public JdbcStockItemQueryPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StockItemView> findById(UUID stockItemId) {
        return jdbc.query(
            SELECT_SQL + " WHERE stock_item_id = ?",
            ROW_MAPPER, stockItemId
        ).stream().findFirst();
    }

    @Override
    public Optional<StockItemView> findByProductId(UUID productId) {
        return jdbc.query(
            SELECT_SQL + " WHERE product_id = ?",
            ROW_MAPPER, productId
        ).stream().findFirst();
    }

    @Override
    public List<StockItemView> findAll() {
        return jdbc.query(SELECT_SQL + " ORDER BY product_sku", ROW_MAPPER);
    }
}
