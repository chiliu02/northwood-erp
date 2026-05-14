package com.northwood.sales.infrastructure.persistence;

import com.northwood.sales.application.inbox.SalesPriceProjection;
import java.math.BigDecimal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSalesPriceProjection implements SalesPriceProjection {

    private static final Logger log = LoggerFactory.getLogger(JdbcSalesPriceProjection.class);

    private final JdbcTemplate jdbc;

    public JdbcSalesPriceProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void applySalesPrice(UUID productId, BigDecimal salesPrice, String currencyCode) {
        jdbc.update("""
            INSERT INTO sales.product_pricing (product_id, sales_price, currency_code)
            VALUES (?, ?, ?)
            ON CONFLICT (product_id) DO UPDATE
                SET sales_price = EXCLUDED.sales_price,
                    currency_code = EXCLUDED.currency_code
            """,
            productId, salesPrice, currencyCode
        );
        log.info("upserted sales.product_pricing for product_id={} → {} {}",
            productId, salesPrice, currencyCode);
    }
}
