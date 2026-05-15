package com.northwood.finance.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Write port for the consolidated {@code finance.product_accounting}
 * projection. Every finance-side product fact (standard cost + currency,
 * valuation class, discontinued-at) lives in one row whose lifetime mirrors
 * the Product aggregate's: seeded on {@code product.ProductCreated},
 * populated by attribute-change events, stamped on
 * {@code product.ProductDiscontinued}.
 *
 * <p>Replaces the previous narrow {@code ProductStandardCostProjection} /
 * {@code ProductValuationClassProjection} ports. The previous tables were
 * named after individual columns rather than after finance's view of the
 * aggregate — see {@code docs/conventions.md} → *Consumer-side projection
 * tables*.
 *
 * <p>Application-side port consumed only by {@code *Handler} classes in
 * this package. Non-handler readers go through
 * {@link com.northwood.finance.application.ProductAccountingLookup}.
 *
 * <p>JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductAccountingProjection}.
 */
public interface ProductAccountingProjection {

    /**
     * Seeds a stub row on {@code product.ProductCreated} so subsequent
     * attribute-change handlers always find a row to {@code UPDATE}. Insert
     * is race-tolerant ({@code ON CONFLICT DO NOTHING}); all attribute
     * columns start NULL.
     */
    void seed(UUID productId);

    /** Updates standard_cost + currency_code from {@code StandardCostChanged}. */
    void applyStandardCost(UUID productId, BigDecimal standardCost, String currencyCode);

    /** Updates valuation_class from {@code ValuationClassChanged}. */
    void applyValuationClass(UUID productId, String valuationClass);

    /** Stamps discontinued_at from {@code ProductDiscontinued}. */
    void applyDiscontinued(UUID productId, Instant discontinuedAt);
}
