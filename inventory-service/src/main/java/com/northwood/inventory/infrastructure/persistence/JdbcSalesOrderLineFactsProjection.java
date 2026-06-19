package com.northwood.inventory.infrastructure.persistence;

import com.northwood.inventory.application.inbox.SalesOrderLineFactsProjection;
import java.math.BigDecimal;
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
    public void applySalesOrderPlaced(UUID salesOrderHeaderId, UUID salesOrderLineId, UUID productId,
                                      BigDecimal orderedQuantity, String paymentTerms) {
        // paymentTerms is null on legacy events emitted before payment-terms
        // support shipped — let the column DEFAULT 'on_shipment' apply by
        // passing null through the COALESCE on conflict and binding 'on_shipment'
        // on the initial INSERT. ON CONFLICT refreshes ordered_quantity but
        // never resets shipped_quantity (a redelivery must not un-claim already
        // shipped units).
        String pt = paymentTerms == null ? "on_shipment" : paymentTerms;
        jdbc.update("""
            INSERT INTO inventory.sales_order_line_facts (
                sales_order_line_id, sales_order_header_id, product_id, ordered_quantity, payment_terms
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (sales_order_line_id) DO UPDATE SET
                sales_order_header_id = EXCLUDED.sales_order_header_id,
                product_id = EXCLUDED.product_id,
                ordered_quantity = EXCLUDED.ordered_quantity,
                payment_terms = EXCLUDED.payment_terms
            """,
            salesOrderLineId, salesOrderHeaderId, productId, orderedQuantity, pt
        );
        log.debug("seeded sales_order_line_facts for line={} (so={}, product={}, orderedQty={}, payment_terms={})",
            salesOrderLineId, salesOrderHeaderId, productId, orderedQuantity, pt);
    }

    @Override
    @Transactional
    public void applyLineQuantityChanged(UUID salesOrderLineId, BigDecimal newOrderedQuantity) {
        jdbc.update("""
            UPDATE inventory.sales_order_line_facts
               SET ordered_quantity = ?
             WHERE sales_order_line_id = ?
            """,
            newOrderedQuantity, salesOrderLineId
        );
    }

    @Override
    @Transactional
    public void applyLineRemoved(UUID salesOrderLineId) {
        // Guard on shipped=0 so this never trips the shipped<=ordered CHECK
        // (removal is gated before any line ships; the guard is defence-in-depth).
        jdbc.update("""
            UPDATE inventory.sales_order_line_facts
               SET ordered_quantity = 0
             WHERE sales_order_line_id = ?
               AND shipped_quantity = 0
            """,
            salesOrderLineId
        );
    }

    @Override
    @Transactional
    public boolean tryClaimShipment(UUID salesOrderLineId, BigDecimal quantity) {
        // Atomic, row-locked claim: bump shipped only if it stays within ordered.
        // Concurrent shipments of one line serialize on this UPDATE's row lock,
        // so the second-past-the-cap matches 0 rows and is rejected. The
        // shipped<=ordered CHECK is the backstop if any path bypasses this WHERE.
        int claimed = jdbc.update("""
            UPDATE inventory.sales_order_line_facts
               SET shipped_quantity = shipped_quantity + ?
             WHERE sales_order_line_id = ?
               AND shipped_quantity + ? <= ordered_quantity
               AND NOT cancelled
            """,
            quantity, salesOrderLineId, quantity
        );
        return claimed == 1;
    }

    @Override
    @Transactional
    public boolean tryClaimCancellation(UUID salesOrderHeaderId) {
        // Mark every not-yet-shipped line cancelled (row-locked; this is what a
        // racing ship-claim's NOT cancelled guard then sees). A line that already
        // shipped keeps cancelled=false and is left intact.
        jdbc.update("""
            UPDATE inventory.sales_order_line_facts
               SET cancelled = true
             WHERE sales_order_header_id = ?
               AND shipped_quantity = 0
            """,
            salesOrderHeaderId
        );
        // Cancellable iff no line of the order has shipped. A shipment that
        // committed before this claim leaves a shipped line → reject.
        Integer shippedLines = jdbc.queryForObject("""
            SELECT count(*) FROM inventory.sales_order_line_facts
             WHERE sales_order_header_id = ?
               AND shipped_quantity > 0
            """,
            Integer.class, salesOrderHeaderId
        );
        return shippedLines == null || shippedLines == 0;
    }

    @Override
    @Transactional
    public void applyUpfrontPaymentSettled(UUID salesOrderHeaderId) {
        int updated = jdbc.update("""
            UPDATE inventory.sales_order_line_facts
               SET upfront_settled = true
             WHERE sales_order_header_id = ?
               AND upfront_settled = false
            """,
            salesOrderHeaderId
        );
        log.info("flipped upfront_settled=true for sales_order={} ({} line(s))",
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
    public Optional<UpfrontPaymentGate> findUpfrontPaymentGate(UUID salesOrderHeaderId) {
        // Every line of the order carries the same (payment_terms,
        // upfront_settled) pair (header-level facts denormalised on each
        // line), so LIMIT 1 returns a representative snapshot.
        return jdbc.query("""
            SELECT payment_terms, upfront_settled
              FROM inventory.sales_order_line_facts
             WHERE sales_order_header_id = ?
             LIMIT 1
            """,
            (rs, n) -> new UpfrontPaymentGate(
                rs.getString("payment_terms"),
                rs.getBoolean("upfront_settled")
            ),
            salesOrderHeaderId
        ).stream().findFirst();
    }
}
