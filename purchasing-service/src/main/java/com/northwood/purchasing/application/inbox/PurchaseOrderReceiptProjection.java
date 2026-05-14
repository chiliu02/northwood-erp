package com.northwood.purchasing.application.inbox;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Applies a goods-receipt event to the purchase-order's lines and header.
 * Bumps each line's {@code received_quantity}, then reclassifies the header
 * status:
 *
 * <ul>
 *   <li>Any line received → {@code 'partially_received'}.</li>
 *   <li>Every line fully received → {@code 'received'}.</li>
 * </ul>
 *
 * <p>CQRS-style write: the {@code PurchaseOrder} aggregate doesn't currently
 * model {@code received_quantity} or {@code paid_amount} (they're columns on
 * the same tables but not loaded into the in-memory aggregate), so receipt
 * application doesn't go through {@code purchaseOrder.applyReceipt(...)}
 * today. Folding receipt state into the aggregate is a separate slice; until
 * then this projection service is the single home for receipt-driven writes
 * to the PO header/line tables, called from the inbox handler.
 *
 * <p>Returns the post-receipt aggregate state ({@code fullyReceived} flag
 * and the running {@code totalReceived} amount) so the caller can decide
 * whether to advance the saga.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcPurchaseOrderReceiptProjection}.
 */
public interface PurchaseOrderReceiptProjection {

    /** Per-line input — pairs the PO line id with the quantity newly received. */
    record ReceiptLine(UUID purchaseOrderLineId, BigDecimal receivedQuantity) {}

    /** Outcome — fully-received flag and the recomputed total received amount. */
    record ReceiptOutcome(boolean fullyReceived, BigDecimal totalReceived) {}

    ReceiptOutcome recordReceipt(UUID purchaseOrderHeaderId, List<ReceiptLine> lines);
}
