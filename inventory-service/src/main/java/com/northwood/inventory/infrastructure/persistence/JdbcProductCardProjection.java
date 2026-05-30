package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.ProductCardProjection;
import com.northwood.product.domain.events.ReorderPolicyChanged;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductCardProjection implements ProductCardProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductCardProjection.class);

    /** Sane default base UOM until ProductCreated's payload carries it or an inventory command authors it. */
    private static final String DEFAULT_BASE_UOM = "EA";

    private final JdbcTemplate jdbc;

    public JdbcProductCardProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyCreated(UUID productId, String sku, String name, String productType) {
        MakeVsBuy defaults = ProductCardProjection.defaultsFor(productType);
        int rows = jdbc.update("""
            INSERT INTO inventory.product_card (
                product_id, product_sku, product_name, product_type, base_uom_code,
                stock_tracking_mode, is_purchased, is_manufactured, reorder_point, reorder_quantity
            ) VALUES (?, ?, ?, ?, ?, 'tracked', ?, ?, 0, 0)
            ON CONFLICT (product_id) DO NOTHING
            """,
            productId, sku, name, productType, DEFAULT_BASE_UOM,
            defaults.isPurchased(), defaults.isManufactured()
        );
        if (rows == 0) {
            log.debug("inventory.product_card row already exists for product_id={} — ProductCreated seed skipped",
                productId);
        } else {
            log.info("created inventory.product_card for product_id={} sku={} type={} (purchased={}, manufactured={})",
                productId, sku, productType, defaults.isPurchased(), defaults.isManufactured());
        }
    }

    @Override
    @Transactional
    public void applyMakeVsBuy(UUID productId, boolean isPurchased, boolean isManufactured) {
        jdbc.update("""
            INSERT INTO inventory.product_card (product_id, is_purchased, is_manufactured)
            VALUES (?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET is_purchased = EXCLUDED.is_purchased,
                    is_manufactured = EXCLUDED.is_manufactured
            """,
            productId, isPurchased, isManufactured
        );
        log.info("upserted inventory.product_card make-vs-buy for product_id={} → purchased={}, manufactured={}",
            productId, isPurchased, isManufactured);
    }

    @Override
    @Transactional
    public void applyReorderPolicy(UUID productId, BigDecimal reorderPoint, BigDecimal reorderQuantity) {
        int rows = jdbc.update("""
            UPDATE inventory.product_card
               SET reorder_point = ?, reorder_quantity = ?
             WHERE product_id = ?
               AND (reorder_point IS DISTINCT FROM ?
                 OR reorder_quantity IS DISTINCT FROM ?)
            """,
            reorderPoint, reorderQuantity, productId, reorderPoint, reorderQuantity
        );
        if (rows == 0) {
            Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory.product_card WHERE product_id = ?",
                Integer.class, productId
            );
            if (existing != null && existing > 0) {
                log.debug("{} for product_id={} ignored — values unchanged (point={}, qty={})",
                    ReorderPolicyChanged.EVENT_TYPE, productId, reorderPoint, reorderQuantity);
            } else {
                log.warn(
                    "{} received for unknown product_id={} — inventory.product_card row missing, projection skipped",
                    ReorderPolicyChanged.EVENT_TYPE, productId
                );
            }
        } else {
            log.info("applied {} for product_id={} → reorder_point={}, reorder_quantity={}",
                ReorderPolicyChanged.EVENT_TYPE, productId, reorderPoint, reorderQuantity);
        }
    }

    @Override
    @Transactional
    public void applyDiscontinued(UUID productId, Instant discontinuedAt) {
        jdbc.update("""
            INSERT INTO inventory.product_card (product_id, is_purchased, is_manufactured, discontinued_at)
            VALUES (?, false, false, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET is_purchased = false,
                    is_manufactured = false,
                    discontinued_at = COALESCE(inventory.product_card.discontinued_at, EXCLUDED.discontinued_at)
            """,
            productId, Timestamp.from(discontinuedAt)
        );
        log.info("discontinued inventory.product_card for product_id={} (at={})", productId, discontinuedAt);
    }
}
