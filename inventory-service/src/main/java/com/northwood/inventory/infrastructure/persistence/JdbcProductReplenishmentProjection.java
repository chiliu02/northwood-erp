package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.ProductReplenishmentProjection;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductReplenishmentProjection implements ProductReplenishmentProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductReplenishmentProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductReplenishmentProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void seedDefaultsFromProductType(UUID productId, String productType) {
        Replenishment defaults = ProductReplenishmentProjection.defaultsFor(productType);
        jdbc.update("""
            INSERT INTO inventory.product_replenishment (product_id, is_purchased, is_manufactured)
            VALUES (?, ?, ?)
            ON CONFLICT (product_id) DO NOTHING
            """,
            productId, defaults.isPurchased(), defaults.isManufactured()
        );
        log.info("seeded inventory.product_replenishment for product_id={} ({}): purchased={}, manufactured={}",
            productId, productType, defaults.isPurchased(), defaults.isManufactured());
    }

    @Override
    @Transactional
    public void applyMakeVsBuy(UUID productId, boolean isPurchased, boolean isManufactured) {
        jdbc.update("""
            INSERT INTO inventory.product_replenishment (product_id, is_purchased, is_manufactured)
            VALUES (?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET is_purchased = EXCLUDED.is_purchased,
                    is_manufactured = EXCLUDED.is_manufactured
            """,
            productId, isPurchased, isManufactured
        );
        log.info("upserted inventory.product_replenishment for product_id={} → purchased={}, manufactured={}",
            productId, isPurchased, isManufactured);
    }

    @Override
    @Transactional
    public void applyDiscontinued(UUID productId) {
        jdbc.update("""
            INSERT INTO inventory.product_replenishment (product_id, is_purchased, is_manufactured, discontinued_at)
            VALUES (?, false, false, now())
            ON CONFLICT (product_id) DO UPDATE
                SET is_purchased = false,
                    is_manufactured = false,
                    discontinued_at = COALESCE(inventory.product_replenishment.discontinued_at, now())
            """,
            productId
        );
        log.info("discontinued inventory.product_replenishment for product_id={}", productId);
    }

    @Override
    public Optional<Replenishment> findByProductId(UUID productId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "SELECT is_purchased, is_manufactured FROM inventory.product_replenishment WHERE product_id = ?",
                (rs, n) -> new Replenishment(rs.getBoolean("is_purchased"), rs.getBoolean("is_manufactured")),
                productId
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
