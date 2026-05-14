package com.northwood.purchasing.application.inbox;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Applies supplier-invoice + supplier-payment events to the
 * {@code purchase_order_header} progress columns ({@code invoiced_amount},
 * {@code paid_amount}, {@code status}). CQRS-style write: the PO aggregate
 * doesn't currently model these, so progression doesn't go through
 * {@code purchaseOrder.applyInvoice(...)} / {@code applyPayment(...)} today.
 * Folding into the aggregate is a separate slice; until then this projection
 * service is the single home for these writes.
 *
 * <p>Three methods because each P2P step touches the header differently:
 * <ul>
 *   <li>{@link #addInvoicedAmount} — invoice approved: bumps
 *       {@code invoiced_amount} additively (partial-then-full invoicing sums
 *       correctly) and flips status to {@code 'partially_invoiced'} /
 *       {@code 'invoiced'}. <strong>Must run before any payment</strong> —
 *       the schema CHECK {@code paid_amount <= invoiced_amount} would
 *       otherwise reject the payment.</li>
 *   <li>{@link #markFullyPaid} — payment fully settled an invoice: flips
 *       status to {@code 'paid'} and sets {@code paid_amount = total_amount}.</li>
 *   <li>{@link #addPartialPayment} — partial payment: adds to the running
 *       {@code paid_amount} and leaves status alone.</li>
 * </ul>
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcPurchaseOrderPaymentProjection}.
 */
public interface PurchaseOrderPaymentProjection {

    void addInvoicedAmount(UUID purchaseOrderHeaderId, BigDecimal invoicedAmount);

    void markFullyPaid(UUID purchaseOrderHeaderId);

    void addPartialPayment(UUID purchaseOrderHeaderId, BigDecimal allocatedAmount);
}
