package com.northwood.finance.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerInvoiceRepository {

    Optional<CustomerInvoice> findById(CustomerInvoiceId id);

    /** All invoices, newest first. Used by the operational UI list view. */
    List<CustomerInvoice> findAll();

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(CustomerInvoice invoice);

    /**
     * Narrow projection for the payment-recording flow: header fields plus
     * the {@code paid_amount} (maintained by the {@code maintain_allocation_totals}
     * DB trigger and not modelled on the {@link CustomerInvoice} aggregate).
     * Returns {@link Optional#empty()} when no row exists.
     */
    Optional<PaymentSnapshot> findPaymentSnapshot(UUID customerInvoiceHeaderId);

    /**
     * Sum of {@code outstanding_amount} (total − paid, the generated column)
     * across <em>all</em> of an order's customer invoices — the order-level
     * outstanding. With partial shipments an order has several per-shipment
     * invoices; the payment flow uses this to compute {@code orderFullySettled}
     * for {@code CustomerPaymentReceived}, so sales' fulfilment saga completes
     * only when the whole order is paid. Returns {@link BigDecimal#ZERO} when
     * the order has no invoices (COALESCE).
     */
    BigDecimal sumOutstandingForOrder(UUID salesOrderHeaderId);

    /**
     * Narrow projection used at shipment-time to (a) skip commercial invoice
     * auto-creation when a prepayment invoice already exists for the order,
     * and (b) post the deferred-revenue Dr 2110 / Cr Revenue pair against an
     * existing prepayment invoice. Carries the {@code revenue_recognized_at}
     * flag so the GL post is idempotent across redeliveries.
     */
    Optional<ShipmentTimeInvoice> findInvoiceForShipment(UUID salesOrderHeaderId);

    /**
     * Stamp {@code revenue_recognized_at} on the invoice. Used by the
     * prepayment revenue-recognition path so the same shipment can't post the
     * Dr 2110 / Cr Revenue pair twice. The repository UPDATEs only when the
     * column is still null — concurrent posters racing on the same shipment
     * land on a balanced "first wins, second is a no-op" outcome.
     *
     * @return true if this call stamped the column, false if it was already
     *         non-null.
     */
    boolean markRevenueRecognized(UUID customerInvoiceHeaderId);

    /**
     * Stamp {@code refunded_at} on the invoice. Used by the cancellation-refund
     * path so the same cancellation can't post the Dr 2110 / Cr 1000 Bank
     * refund pair twice. UPDATEs only when the column is still null —
     * concurrent posters land on "first wins, second no-ops".
     *
     * @return true if this call stamped the column, false if it was already
     *         non-null.
     */
    boolean markRefunded(UUID customerInvoiceHeaderId);

    record PaymentSnapshot(
        UUID customerId,
        String customerName,
        UUID salesOrderHeaderId,
        String currencyCode,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        CustomerInvoice.Status status,
        /** Drives the Cr-side branch at payment posting. */
        CustomerInvoice.InvoiceType invoiceType
    ) {}

    /**
     * The fields a finance shipment-time handler needs: the
     * invoice's id (to mark revenue recognised), its type (to decide whether
     * to post the deferred-revenue pair or skip), the total amount (the Dr
     * 2110 / Cr Revenue pair amount), the currency, the customer name (for
     * the journal-entry description), and whether revenue has already been
     * recognised (idempotency gate).
     */
    record ShipmentTimeInvoice(
        UUID customerInvoiceHeaderId,
        String invoiceNumber,
        CustomerInvoice.InvoiceType invoiceType,
        String customerName,
        String currencyCode,
        BigDecimal totalAmount,
        boolean revenueRecognized
    ) {}
}
