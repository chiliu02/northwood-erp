package com.northwood.finance.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An incoming customer payment has been posted and allocated against a
 * customer invoice. Carries the invoice's resulting state ({@code paid} on
 * full settlement, {@code partially_paid} otherwise) so sales' fulfilment
 * saga knows whether to advance to {@code completed} or hold at
 * {@code invoice_partially_paid} for partial.
 *
 * <p>{@code aggregateId} is the payment id. {@code salesOrderHeaderId} is
 * the saga-routing key for sales.
 */
public record CustomerPaymentReceived(
    UUID eventId,
    UUID aggregateId,
    String paymentNumber,
    UUID customerInvoiceHeaderId,
    UUID salesOrderHeaderId,
    UUID customerId,
    String customerName,
    String paymentMethod,
    String currencyCode,
    BigDecimal amount,
    BigDecimal allocatedAmount,
    String invoiceStatusAfter,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "finance.CustomerPaymentReceived";

    /** Wire-format value of {@link #invoiceStatusAfter} when the payment fully settles the invoice. */
    public static final String INVOICE_STATUS_PAID = "paid";
    /** Wire-format value of {@link #invoiceStatusAfter} when the payment is a partial allocation. */
    public static final String INVOICE_STATUS_PARTIALLY_PAID = "partially_paid";

    @Override public String eventType() { return EVENT_TYPE; }
}
