package com.northwood.inventory.domain;

/**
 * Wire-format constants for the {@code inventory.stock_movement.source_type}
 * column — the polymorphic FK pointer paired with {@code source_id} that
 * names which upstream business object generated the movement.
 *
 * <p>Distinct from {@code outbox.aggregate_type} despite the conceptual
 * overlap: {@code aggregate_type} uses CamelCase Java class names
 * ({@code "GoodsReceipt"}, {@code "Shipment"}, {@code "WorkOrder"} via the
 * {@code *AggregateTypes} holders) and is read by cross-service consumers
 * to dispatch handlers; {@code source_type} uses snake_case audit-trail
 * labels ({@code "goods_receipt"}, {@code "shipment"}, {@code "work_order"})
 * and is read only by inventory's own audit list. Two different wire
 * conventions, two different domains.
 *
 * <p>No schema CHECK — the column is free-form text. The producer side is
 * the three call sites: {@code GoodsReceiptService},
 * {@code ShipmentService}, and {@code WorkOrderManufacturingCompletedHandler}.
 * Constants here pin those values so Find Usages on each name surfaces every
 * write site.
 *
 * <p>Constants holder (not an enum) because the {@code source_type} domain
 * is open: a future stock-adjustment slice may add {@code "manual"} or a
 * specific approver-identity flavour, and the audit list tolerates that
 * without a producer-side enum migration.
 */
public final class StockMovementSourceTypes {

    public static final String GOODS_RECEIPT = "goods_receipt";
    public static final String SHIPMENT = "shipment";
    public static final String WORK_ORDER = "work_order";

    private StockMovementSourceTypes() {}
}
