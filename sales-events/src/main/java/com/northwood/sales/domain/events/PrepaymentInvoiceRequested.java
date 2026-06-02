package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The sales fulfilment saga emits this from {@code started}
 * when the order's {@code payment_terms = 'prepayment'} — finance consumes it
 * and creates a {@code CustomerInvoice} with {@code invoice_type='prepayment'}
 * <b>before</b> any stock reservation or shipment.
 *
 * <p>Payload mirrors what finance needs to build the invoice from sales-side
 * data (pricing, tax, per-line product info) — finance doesn't read from
 * sales' aggregates directly. Lines are the same sales-order lines that would
 * later land in a {@link SalesOrderShipped} payload for the on-shipment path.
 *
 * <p>{@code aggregateId} is the sales-order-header id; the saga itself is the
 * emitter but the order is the consumer-facing key (sales' AGGREGATE_TYPE on
 * the outbox row stays the saga since the saga owns the emission lifecycle).
 *
 * <p><b>Treatment A (GL):</b> finance posts <b>no</b> journal entry at
 * invoice creation — the deposit is recognised only when the customer pays
 * (Dr Cash / Cr 2110 Customer Deposits), and revenue is recognised only at
 * shipment (Dr 2110 / Cr Revenue).
 */
public record PrepaymentInvoiceRequested(
    UUID eventId,
    UUID aggregateId,
    String orderNumber,
    UUID customerId,
    String customerCode,
    String customerName,
    String currencyCode,
    List<RequestedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.PrepaymentInvoiceRequested";

    @Override public String eventType() { return EVENT_TYPE; }

    public record RequestedLine(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal taxRate
    ) {}
}
