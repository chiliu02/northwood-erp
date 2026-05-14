package com.northwood.finance.application.inbox;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Maintains {@code finance.product_standard_cost} from
 * {@code product.StandardCostChanged} events. {@code JournalEntryService}'s
 * COGS posting path reads from this projection at shipment time, so the GL
 * captures finance's authoritative cost rather than whatever value the
 * warehouse clerk typed onto the shipment line.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductStandardCostProjection}.
 */
public interface ProductStandardCostProjection {

    /**
     * §2.8 Slice B: read the product's current standard cost. Returns
     * {@link Optional#empty()} only if the product has never had a row
     * (cold-start race during burst-receive on a fresh-volume boot before
     * the seed Liquibase changeset applies — extremely rare in practice
     * because the seed runs on first boot, ahead of any business event).
     * Callers fall back to the shipment-line-stamped {@code unitCost} per
     * the silent-fallback rule documented in {@code design-notes.md}.
     */
    Optional<BigDecimal> findStandardCost(UUID productId);

    void apply(UUID productId, BigDecimal standardCost, String currencyCode);
}
