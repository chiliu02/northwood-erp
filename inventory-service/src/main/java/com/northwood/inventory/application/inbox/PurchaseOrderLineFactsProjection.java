package com.northwood.inventory.application.inbox;

import java.util.Optional;
import java.util.UUID;

/**
 * Maintains {@code inventory.purchase_order_line_facts}. Seeded by
 * {@link PurchaseOrderCreatedHandler} when {@code purchasing.PurchaseOrderCreated}
 * arrives; read by {@code GoodsReceiptService.post} to validate that each
 * posted receipt line's {@code product_id} matches the originating PO line.
 *
 * <p>Twin of {@link SalesOrderLineFactsProjection} for the inbound (receipt)
 * side. See that interface's Javadoc for the defence-in-depth rationale.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcPurchaseOrderLineFactsProjection}.
 */
public interface PurchaseOrderLineFactsProjection {

    /**
     * Upsert one row keyed on {@code purchase_order_line_id}. ON CONFLICT-safe
     * so a redelivered {@code PurchaseOrderCreated} is a no-op.
     */
    void applyPurchaseOrderCreated(
        UUID purchaseOrderHeaderId,
        UUID purchaseOrderLineId,
        UUID productId
    );

    /**
     * Return the projected {@code product_id} for the named PO line, or empty
     * if no row has been projected yet. Callers (receipt validation) treat
     * empty as "unknown line" → reject.
     */
    Optional<UUID> findProductIdForLine(UUID purchaseOrderLineId);
}
