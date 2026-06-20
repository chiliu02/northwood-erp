package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A purchase order has been cancelled. Two emitters:
 * <ul>
 *   <li>the manual <em>reject-a-draft</em> path — a purchasing manager rejects a
 *       draft PO (wrong supplier, zero-priced lines) via
 *       {@code POST /api/purchase-orders/{id}/reject} ({@code draft → cancelled});</li>
 *   <li>the <em>order-pegged compensation</em> path — a cancelled {@code to_order}
 *       sales line's committed PO is withdrawn ({@code PurchaseOrder.compensateCancel},
 *       reachable for a {@code sent} PO too, before any goods arrive).</li>
 * </ul>
 * In both cases the purchase-to-pay saga terminates at {@code cancelled} in the same
 * transaction.
 *
 * <p>{@code previousStatus} records the status the PO held before cancellation
 * ({@code 'draft'} for a reject, {@code 'sent'} for a typical compensation) so
 * consumers can branch on it. {@code aggregateId} is the purchase-order-header id.
 */
public record PurchaseOrderCancelled(
    UUID eventId,
    UUID aggregateId,             // purchase_order_header_id
    String purchaseOrderNumber,
    UUID supplierId,
    String previousStatus,
    String cancelledBy,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.PurchaseOrderCancelled";

    @Override public String eventType() { return EVENT_TYPE; }
}
