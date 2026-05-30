package com.northwood.inventory.domain;

/**
 * Wire-format aggregate-type constants owned by inventory-service. Single
 * source of truth for every {@code aggregate_type} string this service produces.
 *
 * <p>Producer-side (aggregate roots in {@code inventory-service}) re-exports
 * each value as its own {@code AGGREGATE_TYPE} field for stable call sites.
 * Cross-service consumers (consumer test fixtures, cross-service event
 * stamping) import directly from this class — the {@code inventory-events}
 * jar is the only cross-service contract surface for inventory's wire constants.
 *
 * <p>Convention introduced 2026-05-16 (§2.20).
 */
public final class InventoryAggregateTypes {

    public static final String STOCK_RESERVATION = "StockReservation";
    public static final String GOODS_RECEIPT = "GoodsReceipt";
    public static final String SHIPMENT = "Shipment";
    public static final String STOCK_ADJUSTMENT = "StockAdjustment";
    public static final String REPLENISHMENT_REQUEST = "ReplenishmentRequest";

    private InventoryAggregateTypes() {}
}
