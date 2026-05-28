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

    record PaymentSnapshot(
        UUID customerId,
        String customerName,
        UUID salesOrderHeaderId,
        String currencyCode,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        CustomerInvoice.Status status,
        /** §2.31 Slice B — drives the Cr-side branch at payment posting. */
        CustomerInvoice.InvoiceType invoiceType
    ) {}
}
