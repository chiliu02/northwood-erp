package com.northwood.testharness.inmemory.finance;

import com.northwood.finance.application.ProductCardLookup;
import com.northwood.finance.application.inbox.ProductCardProjection;
import com.northwood.product.domain.ValuationClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory stand-in for the consolidated {@code finance.product_card}
 * projection. One fake implements both the write port
 * ({@link ProductCardProjection}, used by the four inbox handlers) and
 * the read port ({@link ProductCardLookup}, used by
 * {@code JournalEntryService} and {@code ShipmentPostedCogsHandler}) — the
 * production code splits writes vs. reads at the JDBC boundary, but the test
 * fake has no such boundary so collapsing into one object is simpler.
 */
public final class InMemoryProductCard implements ProductCardProjection, ProductCardLookup {

    private static final Row EMPTY = new Row(null, null, null, null);

    private record Row(BigDecimal standardCost, String currencyCode, String valuationClass, Instant discontinuedAt) {}

    private final Map<UUID, Row> byProductId = new HashMap<>();

    @Override
    public Optional<BigDecimal> findStandardCost(UUID productId) {
        Row r = byProductId.get(productId);
        return Optional.ofNullable(r == null ? null : r.standardCost());
    }

    @Override
    public Optional<ValuationClass> findValuationClass(UUID productId) {
        Row r = byProductId.get(productId);
        return Optional.ofNullable(r == null ? null : r.valuationClass())
            .map(ValuationClass::fromDb);
    }

    @Override
    public void seed(UUID productId) {
        byProductId.putIfAbsent(productId, EMPTY);
    }

    @Override
    public void applyStandardCost(UUID productId, BigDecimal standardCost, String currencyCode) {
        Row e = byProductId.getOrDefault(productId, EMPTY);
        byProductId.put(productId, new Row(standardCost, currencyCode, e.valuationClass(), e.discontinuedAt()));
    }

    @Override
    public void applyValuationClass(UUID productId, String valuationClass) {
        Row e = byProductId.getOrDefault(productId, EMPTY);
        byProductId.put(productId, new Row(e.standardCost(), e.currencyCode(), valuationClass, e.discontinuedAt()));
    }

    @Override
    public void applyDiscontinued(UUID productId, Instant discontinuedAt) {
        Row e = byProductId.getOrDefault(productId, EMPTY);
        byProductId.put(productId, new Row(e.standardCost(), e.currencyCode(), e.valuationClass(), discontinuedAt));
    }

    /** Test-only convenience: seed standard cost + currency for a product. */
    public InMemoryProductCard putStandardCost(UUID productId, BigDecimal cost, String currency) {
        applyStandardCost(productId, cost, currency);
        return this;
    }

    /** Test-only convenience: seed valuation class for a product. */
    public InMemoryProductCard putValuationClass(UUID productId, String valuationClass) {
        applyValuationClass(productId, valuationClass);
        return this;
    }
}
