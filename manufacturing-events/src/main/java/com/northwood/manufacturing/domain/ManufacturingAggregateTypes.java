package com.northwood.manufacturing.domain;

/**
 * Wire-format aggregate-type constants owned by manufacturing-service. Single
 * source of truth for every {@code aggregate_type} string this service produces.
 *
 * <p>Producer-side (aggregate roots in {@code manufacturing-service})
 * re-exports each value as its own {@code AGGREGATE_TYPE} field for stable
 * call sites. Cross-service consumers (consumer test fixtures, cross-service
 * event stamping) import directly from this class — the
 * {@code manufacturing-events} jar is the only cross-service contract surface
 * for manufacturing's wire constants.
 *
 * <p>Convention introduced 2026-05-16 (§2.20).
 */
public final class ManufacturingAggregateTypes {

    public static final String WORK_ORDER = "WorkOrder";

    private ManufacturingAggregateTypes() {}
}
