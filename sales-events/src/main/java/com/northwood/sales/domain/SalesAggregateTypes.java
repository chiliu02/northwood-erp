package com.northwood.sales.domain;

/**
 * Wire-format aggregate-type constants owned by sales-service. Single source
 * of truth for every {@code aggregate_type} string this service produces.
 *
 * <p>Producer-side (aggregate roots + saga state-machine in
 * {@code sales-service}) re-exports each value as its own {@code AGGREGATE_TYPE}
 * field for stable call sites. Cross-service consumers (consumer test fixtures,
 * cross-service event stamping like {@code ManufacturingDispatched}) import
 * directly from this class — the {@code sales-events} jar is the only
 * cross-service contract surface for sales' wire constants.
 *
 * <p>Convention introduced 2026-05-16 (§2.20).
 */
public final class SalesAggregateTypes {

    public static final String SALES_ORDER = "SalesOrder";
    public static final String CUSTOMER = "Customer";
    public static final String SALES_ORDER_FULFILMENT_SAGA = "SalesOrderFulfilmentSaga";

    private SalesAggregateTypes() {}
}
