package com.northwood.purchasing.domain;

/**
 * Wire-format aggregate-type constants owned by purchasing-service. Single
 * source of truth for every {@code aggregate_type} string this service produces.
 *
 * <p>Producer-side (aggregate roots in {@code purchasing-service}) re-exports
 * each value as its own {@code AGGREGATE_TYPE} field for stable call sites.
 * Cross-service consumers (consumer test fixtures, cross-service event
 * stamping) import directly from this class — the {@code purchasing-events}
 * jar is the only cross-service contract surface for purchasing's wire constants.
 *
 * <p>Convention introduced 2026-05-16 (§2.20).
 */
public final class PurchasingAggregateTypes {

    public static final String SUPPLIER_PRODUCT_PRICE = "SupplierProductPrice";
    public static final String PURCHASE_REQUISITION = "PurchaseRequisition";
    public static final String PURCHASE_ORDER = "PurchaseOrder";
    public static final String PURCHASE_TO_PAY_SAGA = "PurchaseToPaySaga";

    private PurchasingAggregateTypes() {}
}
