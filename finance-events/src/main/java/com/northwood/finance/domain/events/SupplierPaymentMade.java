package com.northwood.finance.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An outgoing supplier payment has been posted and allocated against an
 * approved supplier invoice. Carries the invoice's final settlement state
 * ({@code paid} on full settlement, {@code partially_paid} otherwise) so
 * purchasing's P2P saga knows whether to close the PO.
 *
 * <p>{@code aggregateId} is the payment id. {@code purchaseOrderHeaderId}
 * is the saga-routing key for purchasing.
 */
public record SupplierPaymentMade(
    UUID eventId,
    UUID aggregateId,
    String paymentNumber,
    UUID supplierInvoiceHeaderId,
    UUID purchaseOrderHeaderId,
    UUID supplierId,
    String supplierName,
    String paymentMethod,
    String currencyCode,
    BigDecimal amount,
    BigDecimal allocatedAmount,
    String invoiceStatusAfter,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "finance.SupplierPaymentMade";

    /** Wire-format value of {@link #invoiceStatusAfter} when the payment fully settles the invoice. */
    public static final String INVOICE_STATUS_PAID = "paid";
    /** Wire-format value of {@link #invoiceStatusAfter} when the payment is a partial allocation. */
    public static final String INVOICE_STATUS_PARTIALLY_PAID = "partially_paid";

    @Override public String eventType() { return EVENT_TYPE; }
}
