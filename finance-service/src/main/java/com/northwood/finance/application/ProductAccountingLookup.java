package com.northwood.finance.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port for the consolidated {@code finance.product_accounting}
 * projection. {@link com.northwood.finance.application.JournalEntryService}
 * reads valuation class at GL-posting time (to pick raw-materials / finished-
 * goods inventory + COGS account codes), and
 * {@link com.northwood.finance.application.inbox.ShipmentPostedCogsHandler}
 * reads standard cost at shipment time (so the GL captures finance's
 * authoritative cost, independent of the warehouse clerk's stamped value).
 *
 * <p>Each method returns {@link Optional#empty()} when the row exists but
 * the corresponding attribute is still NULL (created but never assigned),
 * or when no row exists (cold-start race ahead of the seed). Both cases
 * trigger the same projection-order-tolerant fallback at the caller — see
 * each call site's silent-fallback Javadoc for details.
 *
 * <p>JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductAccountingLookup}.
 */
public interface ProductAccountingLookup {

    Optional<BigDecimal> findStandardCost(UUID productId);

    Optional<String> findValuationClass(UUID productId);
}
