package com.northwood.purchasing.infrastructure.persistence;

import com.northwood.purchasing.application.inbox.PurchaseOrderPaymentProjection;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPurchaseOrderPaymentProjection implements PurchaseOrderPaymentProjection {

    private final JdbcTemplate jdbc;

    public JdbcPurchaseOrderPaymentProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addInvoicedAmount(UUID purchaseOrderHeaderId, BigDecimal invoicedAmount) {
        // Bump invoiced_amount additively; flip status to partially_invoiced
        // / invoiced based on whether the new running total covers the order.
        // The CASE-WHEN is needed because the schema CHECK
        // (paid_amount <= invoiced_amount) requires invoiced_amount to be
        // bumped BEFORE any subsequent payment write.
        //
        // The CHECK (invoiced_amount <= total_amount) is an INTENTIONAL hard
        // backstop: over-invoicing a PO is a real 3-way-match anomaly that must
        // fail loudly, not be silently capped. The stale-total root cause that
        // tripped it (total_amount drifted to 0) is now prevented up front by
        // PurchaseOrder.assertApprovable, and a deterministic 23514 parks via
        // DltRedriver instead of looping. Pinned by JdbcPurchaseOrderPaymentProjectionIT.
        jdbc.update("""
            UPDATE purchasing.purchase_order_header
               SET invoiced_amount = invoiced_amount + ?,
                   status = CASE
                       WHEN invoiced_amount + ? >= total_amount THEN 'invoiced'
                       ELSE 'partially_invoiced'
                   END,
                   version = version + 1
             WHERE purchase_order_header_id = ?
            """,
            invoicedAmount, invoicedAmount, purchaseOrderHeaderId
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markFullyPaid(UUID purchaseOrderHeaderId) {
        // Sets paid_amount = total_amount. Reached only via the P2P saga's
        // applySupplierPaymentMade, which fires only from a payment-receivable
        // state (i.e. after invoiced_amount was bumped), so invoiced_amount ==
        // total_amount here in order. A pay-before-invoice breaches
        // CHECK (paid_amount <= invoiced_amount) by design — the saga gate makes
        // it unreachable in order, and a deterministic 23514 now parks (DltRedriver)
        // rather than looping. Pinned by JdbcPurchaseOrderPaymentProjectionIT.
        jdbc.update("""
            UPDATE purchasing.purchase_order_header
               SET status = 'paid', paid_amount = total_amount, version = version + 1
             WHERE purchase_order_header_id = ?
            """,
            purchaseOrderHeaderId
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addPartialPayment(UUID purchaseOrderHeaderId, BigDecimal allocatedAmount) {
        // CHECK (paid_amount <= invoiced_amount) is an intentional backstop: an
        // over-payment is a real anomaly that must fail loudly (now parked, not
        // looped, by DltRedriver), not be silently capped. Pinned by
        // JdbcPurchaseOrderPaymentProjectionIT.
        jdbc.update("""
            UPDATE purchasing.purchase_order_header
               SET paid_amount = paid_amount + ?, version = version + 1
             WHERE purchase_order_header_id = ?
            """,
            allocatedAmount, purchaseOrderHeaderId
        );
    }
}
