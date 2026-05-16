package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.StockItemProjection;
import com.northwood.product.domain.events.ReorderPolicyChanged;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcStockItemProjection implements StockItemProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcStockItemProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcStockItemProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyReorderPolicy(UUID productId, BigDecimal reorderPoint, BigDecimal reorderQuantity) {
        int rows = jdbc.update("""
            UPDATE inventory.stock_item
               SET reorder_point = ?, reorder_quantity = ?
             WHERE product_id = ?
               AND (reorder_point IS DISTINCT FROM ?
                 OR reorder_quantity IS DISTINCT FROM ?)
            """,
            reorderPoint, reorderQuantity, productId, reorderPoint, reorderQuantity
        );
        if (rows == 0) {
            Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory.stock_item WHERE product_id = ?",
                Integer.class, productId
            );
            if (existing != null && existing > 0) {
                log.debug("{} for product_id={} ignored — values unchanged (point={}, qty={})",
                    ReorderPolicyChanged.EVENT_TYPE, productId, reorderPoint, reorderQuantity);
            } else {
                log.warn(
                    "{} received for unknown product_id={} — inventory.stock_item row missing, projection skipped",
                    ReorderPolicyChanged.EVENT_TYPE, productId
                );
            }
        } else {
            log.info("applied {} for product_id={} → reorder_point={}, reorder_quantity={}",
                ReorderPolicyChanged.EVENT_TYPE, productId, reorderPoint, reorderQuantity);
        }
    }
}
