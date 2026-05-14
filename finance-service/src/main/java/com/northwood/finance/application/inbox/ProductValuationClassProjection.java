package com.northwood.finance.application.inbox;

import java.util.Optional;
import java.util.UUID;

/**
 * Maintains {@code finance.product_valuation_class} from
 * {@code product.ValuationClassChanged} events. GL posting paths consult
 * this projection in {@code JournalEntryService} to pick account codes
 * based on valuation class.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcProductValuationClassProjection}.
 */
public interface ProductValuationClassProjection {

    /**
     * §3.2: read the product's current valuation class (e.g.
     * {@code raw_materials}, {@code finished_goods}). Returns
     * {@link Optional#empty()} if the product has never had a class set —
     * callers fall back to the hardcoded default account in
     * {@code JournalEntryService}.
     */
    Optional<String> findValuationClass(UUID productId);

    void apply(UUID productId, String valuationClass);
}
