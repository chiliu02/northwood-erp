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
    public void applySalesOrderPlaced(UUID salesOrderHeaderId, UUID salesOrderLineId, UUID productId, String paymentTerms) {
        // §2.31 Slice C: paymentTerms is null on events emitted before Slice A
        // shipped — let the column DEFAULT 'on_shipment' apply by passing null
        // through the COALESCE on conflict and binding 'on_shipment' on the
        // initial INSERT.
        String pt = paymentTerms == null ? "on_shipment" : paymentTerms;
        jdbc.update("""
            INSERT INTO inventory.sales_order_line_facts (
                sales_order_line_id, sales_order_header_id, product_id, payment_terms
            ) VALUES (?, ?, ?, ?)
            ON CONFLICT (sales_order_line_id) DO UPDATE SET
                sales_order_header_id = EXCLUDED.sales_order_header_id,
                product_id = EXCLUDED.product_id,
                payment_terms = EXCLUDED.payment_terms
            """,
            salesOrderLineId, salesOrderHeaderId, productId, pt
        );
        log.debug("seeded sales_order_line_facts for line={} (so={}, product={}, payment_terms={})",
            salesOrderLineId, salesOrderHeaderId, productId, pt);
    }

    @Override
    @Transactional
    public void applyPrepaymentSettled(UUID salesOrderHeaderId) {
        int updated = jdbc.update("""
            UPDATE inventory.sales_order_line_facts
               SET prepayment_settled = true
             WHERE sales_order_header_id = ?
               AND prepayment_settled = false
            """,
            salesOrderHeaderId
        );
        log.info("flipped prepayment_settled=true for sales_order={} ({} line(s))",
            salesOrderHeaderId, updated);
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

    @Override
    public Optional<PrepaymentGate> findPrepaymentGate(UUID salesOrderHeaderId) {
        // Every line of the order carries the same (payment_terms,
        // prepayment_settled) pair (header-level facts denormalised on each
        // line), so LIMIT 1 returns a representative snapshot.
        return jdbc.query("""
            SELECT payment_terms, prepayment_settled
              FROM inventory.sales_order_line_facts
             WHERE sales_order_header_id = ?
             LIMIT 1
            """,
            (rs, n) -> new PrepaymentGate(
                rs.getString("payment_terms"),
                rs.getBoolean("prepayment_settled")
            ),
            salesOrderHeaderId
        ).stream().findFirst();
    }
}
