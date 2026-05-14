package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.ProductCreatedProjection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductCreatedProjection implements ProductCreatedProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductCreatedProjection.class);

    /** Sane default base UOM until ProductCreated's payload carries it or an inventory command authors it. */
    private static final String DEFAULT_BASE_UOM = "EA";

    private final JdbcTemplate jdbc;

    public JdbcProductCreatedProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void apply(UUID productId, String sku, String name, String productType) {
        int rows = jdbc.update("""
            INSERT INTO inventory.stock_item (
                product_id, product_sku, product_name, product_type, base_uom_code,
                stock_tracking_mode, reorder_point, reorder_quantity
            ) VALUES (?, ?, ?, ?, ?, 'tracked', 0, 0)
            ON CONFLICT (product_id) DO NOTHING
            """,
            productId, sku, name, productType, DEFAULT_BASE_UOM
        );
        if (rows == 0) {
            log.debug("inventory.stock_item row already exists for product_id={} — ProductCreated stub skipped",
                productId);
        } else {
            log.info("created inventory.stock_item stub for product_id={} sku={} type={}",
                productId, sku, productType);
        }
    }
}
