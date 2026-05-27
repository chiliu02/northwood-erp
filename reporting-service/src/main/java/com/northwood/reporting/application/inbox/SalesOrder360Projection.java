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
        Instant occurredAt,
        String eventType,
        String actorUserId);

    void recordManufacturingCompleted(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId);

    /**
     * Record that the sales fulfilment saga has reached {@code ready_to_ship}
     * (every line reserved from stock or produced): advance {@code order_status}
     * to {@code 'ready_to_ship'} so the shipment UI's order picker surfaces it.
     * Never downgrades a terminal {@code 'cancelled'}. Idempotent.
     */
    void recordReadyToShip(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId);

    /**
     * Record that the sales fulfilment saga has finished compensation: flip
     * {@code order_status} to {@code 'cancelled'}.
     */
    void recordCancellation(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId);

    /**
     * Record a posted shipment: set {@code shipment_status='shipped'} and
     * advance {@code order_status} to {@code 'shipped'} (forward-only; never
     * downgrades a later state, preserves {@code 'cancelled'}).
     */
    void recordShipment(UUID salesOrderHeaderId, Instant occurredAt, String actorUserId);

    void recordInvoice(UUID salesOrderHeaderId, BigDecimal invoiced, Instant occurredAt, String actorUserId);

    /**
     * Record a customer payment: bump {@code paid_amount}/{@code outstanding}
     * and set {@code payment_status}. On full settlement
     * ({@code invoiceStatusAfter == paid}) advance {@code order_status} to
     * {@code 'completed'} (forward-only; preserves {@code 'cancelled'}).
     */
    void recordPayment(
        UUID salesOrderHeaderId,
        BigDecimal allocated,
        String invoiceStatusAfter,
        Instant occurredAt,
        String actorUserId);
}
