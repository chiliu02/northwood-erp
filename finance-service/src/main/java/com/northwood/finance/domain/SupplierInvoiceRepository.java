package com.northwood.finance.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierInvoiceRepository {

    Optional<SupplierInvoice> findById(SupplierInvoiceId id);

    /** Insert + emit pending events to the outbox in the same transaction. */
    void save(SupplierInvoice invoice);

    /** List invoices in a given status. Used by the manual-review UI. */
    List<SupplierInvoice> findByStatus(SupplierInvoice.Status status);

    /** All invoices, newest first. Used by the operational AP-payment picker. */
    List<SupplierInvoice> findAll();

    /**
     * Narrow projection for the payment-recording flow: header fields plus
     * the {@code paid_amount} (maintained by the {@code maintain_allocation_totals}
     * DB trigger and not modelled on the {@link SupplierInvoice} aggregate).
     * Returns {@link Optional#empty()} when no row exists.
     */
    Optional<PaymentSnapshot> findPaymentSnapshot(UUID supplierInvoiceHeaderId);

    record PaymentSnapshot(
        UUID supplierId,
        String supplierName,
        UUID purchaseOrderHeaderId,
        String currencyCode,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        SupplierInvoice.Status status
    ) {}
}
