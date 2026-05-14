package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maintains {@code reporting.purchase_order_tracking_view}. Mirror of
 * {@link SalesOrder360Projection} for the P2P side.
 *
 * <p>Order-tolerant by design — events arrive on independent kafka topics.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcPurchaseOrderTrackingProjection}.
 */
public interface PurchaseOrderTrackingProjection {

    void createFromPurchaseOrder(
        UUID purchaseOrderHeaderId,
        String purchaseOrderNumber,
        UUID supplierId,
        String supplierName,
        String poStatus,
        String currencyCode,
        BigDecimal orderedAmount,
        UUID sourceWorkOrderId,
        Instant occurredAt,
        String actorUserId);

    /**
     * §2.1: look up the source work-order id stamped on a PO's tracking row.
     * Returns empty when (a) no row exists yet (event-arrival race), or
     * (b) the PO wasn't shortage-driven. Callers chain this with
     * {@link #countOpenForWorkOrder} to recompute the planning-board count.
     */
    java.util.Optional<UUID> findSourceWorkOrderForPo(UUID purchaseOrderHeaderId);

    /**
     * §2.1: count open POs for a given work order. "Open" = po_status NOT IN
     * ('received', 'paid', 'cancelled'). Used to drive
     * {@code production_planning_board.open_purchase_orders_count}.
     */
    int countOpenForWorkOrder(UUID workOrderId);

    void recordGoodsReceived(
        UUID purchaseOrderHeaderId,
        UUID goodsReceiptHeaderId,
        BigDecimal receivedDelta,
        Instant occurredAt,
        String actorUserId);

    void recordInvoiceApproved(
        UUID purchaseOrderHeaderId,
        UUID supplierInvoiceHeaderId,
        BigDecimal invoiceAmount,
        Instant occurredAt,
        String actorUserId);

    void recordPayment(
        UUID purchaseOrderHeaderId,
        UUID paymentId,
        BigDecimal allocatedAmount,
        String invoiceStatusAfter,
        Instant occurredAt,
        String actorUserId);
}
