package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The sales fulfilment saga emits this from {@code started}
 * when the order's {@code payment_terms = 'deposit'} — finance consumes it and
 * creates a {@code CustomerInvoice} with {@code invoice_type='deposit'}
 * <b>before</b> any stock reservation or shipment.
 *
 * <p>Unlike {@link PrepaymentInvoiceRequested} (which carries the full order
 * lines because the prepayment invoice is the whole order), a deposit is a
 * payment-on-account for a <i>fraction</i> of the order, so this event carries
 * the single computed {@code depositAmount} ({@code total × depositPercent / 100})
 * rather than line detail — finance builds a one-line "deposit" invoice from it
 * (the real-ERP down-payment-request shape: a header amount, not material
 * lines). {@code depositPercent} rides along only for the line description /
 * audit.
 *
 * <p>{@code aggregateId} is the sales-order-header id; the saga is the emitter
 * but the order is the consumer-facing key.
 *
 * <p><b>Treatment A (GL):</b> finance posts <b>no</b> journal entry at invoice
 * creation — the deposit is recognised only when the customer pays (Dr Cash /
 * Cr 2110 Customer Deposits), and revenue is recognised at shipment.
 */
public record DepositInvoiceRequested(
    UUID eventId,
    UUID aggregateId,
    String orderNumber,
    UUID customerId,
    String customerCode,
    String customerName,
    String currencyCode,
    BigDecimal depositAmount,
    BigDecimal depositPercent,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.DepositInvoiceRequested";

    @Override public String eventType() { return EVENT_TYPE; }
}
