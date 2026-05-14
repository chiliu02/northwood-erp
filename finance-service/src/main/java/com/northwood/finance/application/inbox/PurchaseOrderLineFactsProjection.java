package com.northwood.finance.application.inbox;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maintains {@code finance.purchase_order_line_facts}. Two write paths:
 *
 * <ul>
 *   <li>{@link #applyPurchaseOrderCreated} — seeds a row from each PO line on
 *       {@code purchasing.PurchaseOrderCreated}. Uses
 *       {@code ON CONFLICT DO UPDATE} so a redelivered event is safe.</li>
 *   <li>{@link #applyGoodsReceived} — bumps {@code received_quantity}
 *       on each receipt line (matched by {@code purchase_order_line_id}).
 *       The bump is additive — partial-then-full-receipt sums correctly.</li>
 * </ul>
 *
 * <p>3-way match uses these rows as the authoritative source. Invoiced
 * quantities are bumped by {@link SupplierInvoiceService} when an invoice
 * posts.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcPurchaseOrderLineFactsProjection}.
 */
public interface PurchaseOrderLineFactsProjection {

    void applyPurchaseOrderCreated(
        UUID purchaseOrderHeaderId,
        UUID supplierId,
        String supplierName,
        String currencyCode,
        UUID purchaseOrderLineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice
    );

    void applyGoodsReceived(UUID purchaseOrderLineId, BigDecimal receivedQuantity);

    void bumpInvoiced(UUID purchaseOrderLineId, BigDecimal invoicedQuantity);

    LineFacts findByLineId(UUID purchaseOrderLineId);

    record LineFacts(
        UUID purchaseOrderLineId,
        UUID purchaseOrderHeaderId,
        UUID supplierId,
        String supplierName,
        String currencyCode,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice,
        BigDecimal receivedQuantity,
        BigDecimal invoicedQuantity
    ) {}
}
