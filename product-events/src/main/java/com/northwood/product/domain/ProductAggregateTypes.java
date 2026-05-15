package com.northwood.product.domain;

/**
 * Wire-format aggregate-type constants owned by product-service. Single source
 * of truth for every {@code aggregate_type} string this service produces.
 *
 * <p>Producer-side (aggregate roots in {@code product-service}) re-exports
 * each value as its own {@code AGGREGATE_TYPE} field for stable call sites.
 * Cross-service consumers (consumer test fixtures, cross-service event
 * stamping) import directly from this class — the {@code product-events} jar
 * is the only cross-service contract surface for product's wire constants.
 *
 * <p>Convention introduced 2026-05-16 (§2.20).
 */
public final class ProductAggregateTypes {

    public static final String PRODUCT = "Product";

    private ProductAggregateTypes() {}
}
