package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSalesOrderLineFactsProjection implements SalesOrderLineFactsProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcSalesOrderLineFactsProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcSalesOrderLineFactsProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applySalesOrderPlaced(UUID salesOrderHeaderId, UUID salesOrderLineId, UUID productId) {
        jdbc.update("""
            INSERT INTO inventory.sales_order_line_facts (
                sales_order_line_id, sales_order_header_id, product_id
            ) VALUES (?, ?, ?)
            ON CONFLICT (sales_order_line_id) DO UPDATE SET
                sales_order_header_id = EXCLUDED.sales_order_header_id,
                product_id = EXCLUDED.product_id
            """,
            salesOrderLineId, salesOrderHeaderId, productId
        );
        log.debug("seeded sales_order_line_facts for line={} (so={}, product={})",
            salesOrderLineId, salesOrderHeaderId, productId);
    }

    @Override
    public Optional<UUID> findProductIdForLine(UUID salesOrderLineId) {
        return jdbc.query("""
            SELECT product_id
              FROM inventory.sales_order_line_facts
             WHERE sales_order_line_id = ?
            """,
            (rs, n) -> rs.getObject("product_id", UUID.class),
            salesOrderLineId
        ).stream().findFirst();
    }
}
