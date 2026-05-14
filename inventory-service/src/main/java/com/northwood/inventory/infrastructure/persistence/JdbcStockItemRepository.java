package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.domain.StockItem;
import com.northwood.inventory.domain.StockItemId;
import com.northwood.inventory.domain.StockItemRepository;
import com.northwood.inventory.domain.StockTrackingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockItemRepository implements StockItemRepository {

    private static final RowMapper<StockItem> ROW_MAPPER = (rs, n) -> StockItem.reconstitute(
        StockItemId.of(rs.getObject("stock_item_id", UUID.class)),
        rs.getObject("product_id", UUID.class),
        rs.getString("product_sku"),
        rs.getString("product_name"),
        rs.getString("product_type"),
        rs.getString("base_uom_code"),
        StockTrackingMode.fromDb(rs.getString("stock_tracking_mode")),
        rs.getBigDecimal("reorder_point"),
        rs.getBigDecimal("reorder_quantity"),
        rs.getLong("version")
    );

    private final JdbcTemplate jdbc;

    public JdbcStockItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StockItem> findById(StockItemId id) {
        return jdbc.query(
            """
            SELECT stock_item_id, product_id, product_sku, product_name, product_type,
                   base_uom_code, stock_tracking_mode,
                   reorder_point, reorder_quantity, version
            FROM inventory.stock_item WHERE stock_item_id = ?
            """,
            ROW_MAPPER, id.value()
        ).stream().findFirst();
    }

    @Override
    public Optional<StockItem> findByProductId(UUID productId) {
        return jdbc.query(
            """
            SELECT stock_item_id, product_id, product_sku, product_name, product_type,
                   base_uom_code, stock_tracking_mode,
                   reorder_point, reorder_quantity, version
            FROM inventory.stock_item WHERE product_id = ?
            """,
            ROW_MAPPER, productId
        ).stream().findFirst();
    }

    @Override
    public List<StockItem> findAll() {
        return jdbc.query(
            """
            SELECT stock_item_id, product_id, product_sku, product_name, product_type,
                   base_uom_code, stock_tracking_mode,
                   reorder_point, reorder_quantity, version
            FROM inventory.stock_item
            ORDER BY product_sku
            """,
            ROW_MAPPER
        );
    }

    @Override
    public void save(StockItem item) {
        // Today inventory's stock_item rows are only ever projected (created
        // in seed, updated by inbox handlers). There is no inventory-side
        // command that mints new stock_items yet, so save() is UPDATE-only
        // and uses optimistic concurrency on the version column.
        int rows = jdbc.update("""
            UPDATE inventory.stock_item SET
                product_sku = ?, product_name = ?, product_type = ?, base_uom_code = ?,
                stock_tracking_mode = ?,
                reorder_point = ?, reorder_quantity = ?,
                version = version + 1
            WHERE stock_item_id = ? AND version = ?
            """,
            item.productSku(), item.productName(), item.productType(), item.baseUomCode(),
            item.trackingMode().dbValue(),
            item.reorderPoint(), item.reorderQuantity(),
            item.id().value(), item.version()
        );
        if (rows == 0) {
            throw new OptimisticLockingFailureException(
                "StockItem " + item.id().value() + " was modified by another transaction"
            );
        }
        // No outbox writes today: inventory does not yet emit projection
        // events. When inventory gains its own commands (e.g. stock
        // adjustments), pull pendingEvents and write to inventory.outbox_message
        // in the same transaction — mirror JdbcProductRepository.
    }

}
