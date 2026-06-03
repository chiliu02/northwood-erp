package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A purchase order has been cancelled. Today this is emitted only from the
 * manual <em>reject-a-draft</em> path — a purchasing manager rejects a draft PO
 * (e.g. wrong supplier, zero-priced lines that can't be approved) via
 * {@code POST /api/purchase-orders/{id}/reject}. The PO flips
 * {@code draft → cancelled} and its purchase-to-pay saga terminates at
 * {@code cancelled} in the same transaction.
 *
 * <p>{@code previousStatus} records the status the PO held before cancellation
 * ({@code 'draft'} today) so a future cancel-after-sent flow can branch
 * compensation on it. {@code aggregateId} is the purchase-order-header id.
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
