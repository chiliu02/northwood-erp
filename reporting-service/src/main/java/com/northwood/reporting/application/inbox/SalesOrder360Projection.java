package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maintains {@code reporting.sales_order_360_view}. One row per sales-order
 * header, stitched together from events emitted by every upstream service.
 *
 * <p>Order-tolerant by design: every method uses
 * {@code INSERT ... ON CONFLICT DO UPDATE}.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcSalesOrder360Projection}.
 */
public interface SalesOrder360Projection {

    void createFromOrder(
        UUID salesOrderHeaderId,
        String orderNumber,
        UUID customerId,
        String customerName,
        LocalDate orderDate,
        LocalDate requestedDeliveryDate,
        String currencyCode,
        BigDecimal totalAmount,
        /** Commercial terms wire value ({@code on_shipment} / {@code prepayment}); nullable for backward compat. */
        String paymentTerms,
        Instant occurredAt,
        String eventType,
        String actorUserId);

    /**
     * Refresh the header money after a sales-order line amendment (§1G.3): set
     * {@code total_amount} to the order's recomputed total and re-derive
     * {@code outstanding_amount} ({@code total − paid_amount}). All three
     * line-amendment events ({@code SalesOrderLineAdded} /
     * {@code SalesOrderLineQuantityChanged} / {@code SalesOrderLineRemoved})
     * carry the post-amendment order total, so this one method serves them all.
     * Idempotent — overwriting with the same total is a no-op; the 360 row
     * already exists (amendment rides the same {@code aggregateId} partition as
     * the placement).
     */
    void recordAmendedTotal(UUID salesOrderHeaderId, BigDecimal newOrderTotal, Instant occurredAt, String eventType, String actorUserId);

    void recordManufacturingCompleted(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId);

    /**
     * Record the inventory reservation outcome for the order: set
     * {@code stock_status} to {@code stockStatus} ({@code reserved} /
     * {@code partially_reserved} / {@code failed}). A full {@code 'reserved'}
     * is sticky — never rolled back by a later partial/stale event. Idempotent.
     */
    void recordStockReserved(UUID salesOrderHeaderId, String stockStatus, Instant occurredAt, String actorUserId);

    /**
     * Record that the sales fulfilment saga has reached {@code ready_to_ship}
     * (every line reserved from stock or produced): advance {@code order_status}
     * to {@code 'ready_to_ship'} so the shipment UI's order picker surfaces it.
     * Also marks {@code manufacturing_status='not_required'} when it is still
     * {@code 'pending'} (a stock-covered order skipped manufacturing), and lifts
     * {@code stock_status} to {@code 'reserved'} from any not-yet-covered state
     * ({@code pending}/{@code failed}/{@code partially_reserved}) — the only path
     * that clears a buy-to-order line's {@code 'failed'}, since its pegged supply
     * signals the saga via {@code ReplenishmentFulfilled}, not a fresh
     * {@code inventory.StockReserved}. Never downgrades a terminal
     * {@code 'cancelled'}. Idempotent.
     */
    void recordReadyToShip(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId);

    /**
     * Record that the sales fulfilment saga has finished compensation: flip
     * {@code order_status} to {@code 'cancelled'}, release a {@code 'reserved'}
     * {@code stock_status} to {@code 'released'}, and — when the order had taken
     * money (a paid prepayment/deposit, the only invoice a pre-shipment cancel
     * can have) — set {@code payment_status='refunded'} to mirror finance's
     * automatic Dr 2110 / Cr 1000 refund.
     */
    void recordCancellation(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId);

    /**
     * Record a posted shipment (driven by {@code sales.SalesOrderShipped}, which
     * carries {@code orderFullyShipped} — only sales knows ordered-vs-shipped).
     * <ul>
     *   <li>Fully shipped — {@code shipment_status='shipped'} and advance
     *       {@code order_status}: a prepaid order (settled before shipment) goes
     *       straight to {@code 'completed'}; otherwise {@code 'shipped'}
     *       (on_shipment/deposit owe the post-ship balance, completed later by
     *       {@link #recordPayment}).</li>
     *   <li>Partial — {@code shipment_status='partially_shipped'} and
     *       {@code order_status} is left unchanged so the order stays pickable
     *       (the shipment UI filters on {@code 'ready_to_ship'}) for the
     *       backorder.</li>
     * </ul>
     * Forward-only: a {@code 'shipped'} shipment_status is never downgraded back
     * to {@code 'partially_shipped'}; never downgrades a later order_status,
     * preserves {@code 'cancelled'}.
     */
    void recordShipment(UUID salesOrderHeaderId, boolean orderFullyShipped, Instant occurredAt, String actorUserId);

    void recordInvoice(UUID salesOrderHeaderId, BigDecimal invoiced, Instant occurredAt, String actorUserId);

    /**
     * Record a customer payment: bump {@code paid_amount}/{@code outstanding}
     * and set {@code payment_status} from the ORDER-level balance
     * ({@code paid}/{@code partially_paid}/{@code pending}) — a deposit invoice
     * settling to paid leaves the order {@code partially_paid} while its balance
     * is outstanding. Advance {@code order_status} to {@code 'completed'} only
     * when the order is both fully settled ({@code paid_amount} covers
     * {@code total_amount}) AND already {@code 'shipped'} — so a full PREPAYMENT
     * (paid before the goods ship) does not complete the order here;
     * {@link #recordShipment} completes it once shipped. Forward-only; preserves
     * {@code 'cancelled'}. {@code invoiceStatusAfter} seeds only the stub-row
     * INSERT path.
     */
    void recordPayment(
        UUID salesOrderHeaderId,
        BigDecimal allocated,
        String invoiceStatusAfter,
        Instant occurredAt,
        String actorUserId);
}
