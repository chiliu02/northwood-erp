package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.PurchasableProductLookup;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPurchasableProductLookup implements PurchasableProductLookup {

    private static final Logger log = LoggerFactory.getLogger(JdbcPurchasableProductLookup.class);

    private final JdbcTemplate jdbc;

    public JdbcPurchasableProductLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isPurchasable(UUID productId) {
        // Returns null when no card row exists yet → treated as purchasable
        // (fail-open, see PurchasableProductLookup + docs/design-notes.md #11).
        // Only a row that positively says is_purchased = false rejects the line.
        Boolean purchased = jdbc.query(
            "SELECT is_purchased FROM purchasing.product_card WHERE product_id = ?",
            rs -> rs.next() ? rs.getBoolean(1) : null,
            productId
        );
        if (purchased == null) {
            // Designed-tolerant: a product_card row not yet projected (ProductCreated
            // lag) reads as purchasable rather than blocking a legitimate buy.
            log.debug("no purchasing.product_card row for product_id={} — treating as purchasable (fail-open)",
                productId);
            return true;
        }
        return purchased;
    }
}
