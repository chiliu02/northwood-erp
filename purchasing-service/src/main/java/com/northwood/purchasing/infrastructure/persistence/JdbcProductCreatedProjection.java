package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.inbox.ProductCreatedProjection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductCreatedProjection implements ProductCreatedProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductCreatedProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductCreatedProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyCreated(UUID productId, String productSku, String productName, String productType) {
        boolean defaultPurchased = ProductCreatedProjection.defaultPurchased(productType);
        // is_purchased is set on insert only; the on-conflict path deliberately
        // leaves it alone so an out-of-order MakeVsBuyChanged (the authority)
        // that already landed isn't clobbered by this type-derived default.
        jdbc.update("""
            INSERT INTO purchasing.product_card (product_id, product_sku, product_name, is_purchased)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET product_sku = EXCLUDED.product_sku,
                    product_name = EXCLUDED.product_name
            """,
            productId, productSku, productName, defaultPurchased
        );
        log.info("upserted purchasing.product_card name snapshot for product_id={} (sku={}, default purchased={})",
            productId, productSku, defaultPurchased);
    }
}
