package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.inbox.CustomerInvoiceCollectionsProjection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcCustomerInvoiceCollectionsProjection implements CustomerInvoiceCollectionsProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcCustomerInvoiceCollectionsProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcCustomerInvoiceCollectionsProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int flagOutstandingForCollections(UUID customerId) {
        int rows = jdbc.update("""
            UPDATE finance.customer_invoice_header
            SET flagged_for_collections = true
            WHERE customer_id = ?
              AND status IN ('posted', 'partially_paid')
              AND outstanding_amount > 0
              AND flagged_for_collections = false
            """,
            customerId
        );
        if (rows == 0) {
            log.info("no outstanding finance.customer_invoice_header rows to flag for customer_id={}", customerId);
        } else {
            log.info("flagged {} outstanding finance.customer_invoice_header row(s) for collections (customer_id={})",
                rows, customerId);
        }
        return rows;
    }
}
