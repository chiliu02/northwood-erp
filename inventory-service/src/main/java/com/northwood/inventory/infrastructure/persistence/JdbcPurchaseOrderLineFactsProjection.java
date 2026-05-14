package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.PurchaseOrderLineFactsProjection;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPurchaseOrderLineFactsProjection implements PurchaseOrderLineFactsProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcPurchaseOrderLineFactsProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcPurchaseOrderLineFactsProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applyPurchaseOrderCreated(UUID purchaseOrderHeaderId, UUID purchaseOrderLineId, UUID productId) {
        jdbc.update("""
            INSERT INTO inventory.purchase_order_line_facts (
                purchase_order_line_id, purchase_order_header_id, product_id
            ) VALUES (?, ?, ?)
            ON CONFLICT (purchase_order_line_id) DO UPDATE SET
                purchase_order_header_id = EXCLUDED.purchase_order_header_id,
                product_id = EXCLUDED.product_id
            """,
            purchaseOrderLineId, purchaseOrderHeaderId, productId
        );
        log.debug("seeded purchase_order_line_facts for line={} (po={}, product={})",
            purchaseOrderLineId, purchaseOrderHeaderId, productId);
    }

    @Override
    public Optional<UUID> findProductIdForLine(UUID purchaseOrderLineId) {
        return jdbc.query("""
            SELECT product_id
              FROM inventory.purchase_order_line_facts
             WHERE purchase_order_line_id = ?
            """,
            (rs, n) -> rs.getObject("product_id", UUID.class),
            purchaseOrderLineId
        ).stream().findFirst();
    }
}
