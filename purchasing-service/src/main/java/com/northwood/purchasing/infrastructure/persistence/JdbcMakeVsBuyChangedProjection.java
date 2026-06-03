package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.inbox.MakeVsBuyChangedProjection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMakeVsBuyChangedProjection implements MakeVsBuyChangedProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcMakeVsBuyChangedProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcMakeVsBuyChangedProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyMakeVsBuy(UUID productId, boolean isPurchased) {
        // Upsert: a MakeVsBuyChanged may arrive before ProductCreated's seed
        // (out-of-order delivery), so insert a stub row carrying just the flag;
        // a later ProductCreated fills sku/name and leaves is_purchased alone.
        jdbc.update("""
            INSERT INTO purchasing.product_card (product_id, is_purchased)
            VALUES (?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET is_purchased = EXCLUDED.is_purchased
            """,
            productId, isPurchased
        );
        log.info("upserted purchasing.product_card make-vs-buy for product_id={} → purchased={}",
            productId, isPurchased);
    }
}
