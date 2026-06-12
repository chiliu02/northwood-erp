package com.northwood.finance.application;

import com.northwood.product.domain.ValuationClass;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Read port for the consolidated {@code finance.product_card}
 * projection (finance's consumer-side denormalized record per Product —
 * see {@code docs/conventions.md} → *Consumer-side denormalized tables*).
 * {@link com.northwood.finance.application.JournalEntryService} reads
 * valuation class at GL-posting time (to pick raw-materials / finished-
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
 * {@code infrastructure/persistence/JdbcProductCardLookup}.
 */
public interface ProductCardLookup {

    Optional<BigDecimal> findStandardCost(UUID productId);

    /**
     * Returns the product's valuation class as the typed
     * {@link ValuationClass} enum. The underlying column is a wire-format
     * String (mirrors {@code product.product.valuation_class}); the read
     * path converts via {@link ValuationClass#fromCode} so consumers can
     * switch over the enum. An unknown value in the projection column
     * surfaces as {@link IllegalArgumentException} — the schema CHECK on
     * {@code finance.product_card.valuation_class} keeps the set aligned.
     */
    Optional<ValuationClass> findValuationClass(UUID productId);
}
