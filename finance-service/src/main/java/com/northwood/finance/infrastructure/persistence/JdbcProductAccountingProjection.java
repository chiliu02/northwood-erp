package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.application.inbox.ProductAccountingProjection;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductAccountingProjection implements ProductAccountingProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcProductAccountingProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcProductAccountingProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void seed(UUID productId) {
        int rows = jdbc.update("""
            INSERT INTO finance.product_accounting (product_id)
            VALUES (?)
            ON CONFLICT (product_id) DO NOTHING
            """,
            productId
        );
        if (rows == 0) {
            log.debug("finance.product_accounting row already exists for product_id={} — ProductCreated stub skipped",
                productId);
        } else {
            log.info("created finance.product_accounting stub for product_id={}", productId);
        }
    }

    @Override
    @Transactional
    public void applyStandardCost(UUID productId, BigDecimal standardCost, String currencyCode) {
        String currency = currencyCode == null ? "AUD" : currencyCode;
        int rows = jdbc.update("""
            UPDATE finance.product_accounting
               SET standard_cost = ?,
                   currency_code = ?
             WHERE product_id = ?
            """,
            standardCost, currency, productId
        );
        if (rows == 0) {
            log.warn("StandardCostChanged for product_id={} found no finance.product_accounting row — "
                + "ProductCreated seed missed or replayed out of order; falling back to insert",
                productId);
            jdbc.update("""
                INSERT INTO finance.product_accounting (product_id, standard_cost, currency_code)
                VALUES (?, ?, ?)
                ON CONFLICT (product_id) DO UPDATE SET
                    standard_cost = EXCLUDED.standard_cost,
                    currency_code = EXCLUDED.currency_code
                """,
                productId, standardCost, currency
            );
        } else {
            log.info("updated finance.product_accounting.standard_cost for product_id={} → {} {}",
                productId, standardCost, currency);
        }
    }

    @Override
    @Transactional
    public void applyValuationClass(UUID productId, String valuationClass) {
        int rows = jdbc.update("""
            UPDATE finance.product_accounting
               SET valuation_class = ?
             WHERE product_id = ?
            """,
            valuationClass, productId
        );
        if (rows == 0) {
            log.warn("ValuationClassChanged for product_id={} found no finance.product_accounting row — "
                + "ProductCreated seed missed or replayed out of order; falling back to insert",
                productId);
            jdbc.update("""
                INSERT INTO finance.product_accounting (product_id, valuation_class)
                VALUES (?, ?)
                ON CONFLICT (product_id) DO UPDATE SET
                    valuation_class = EXCLUDED.valuation_class
                """,
                productId, valuationClass
            );
        } else {
            log.info("updated finance.product_accounting.valuation_class for product_id={} → '{}'",
                productId, valuationClass);
        }
    }

    @Override
    @Transactional
    public void applyDiscontinued(UUID productId, Instant discontinuedAt) {
        int rows = jdbc.update("""
            UPDATE finance.product_accounting
               SET discontinued_at = ?
             WHERE product_id = ?
            """,
            Timestamp.from(discontinuedAt), productId
        );
        if (rows == 0) {
            log.warn("ProductDiscontinued for product_id={} found no finance.product_accounting row — "
                + "ProductCreated seed missed or replayed out of order; falling back to insert",
                productId);
            jdbc.update("""
                INSERT INTO finance.product_accounting (product_id, discontinued_at)
                VALUES (?, ?)
                ON CONFLICT (product_id) DO UPDATE SET
                    discontinued_at = EXCLUDED.discontinued_at
                """,
                productId, Timestamp.from(discontinuedAt)
            );
        } else {
            log.info("stamped finance.product_accounting.discontinued_at for product_id={} (at={})",
                productId, discontinuedAt);
        }
    }
}
