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
        jdbc.update("""
            UPDATE purchasing.purchase_order_header
               SET paid_amount = paid_amount + ?, version = version + 1
             WHERE purchase_order_header_id = ?
            """,
            allocatedAmount, purchaseOrderHeaderId
        );
    }
}
