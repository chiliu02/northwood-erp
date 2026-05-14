package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Purchase order has been approved and is now {@code 'sent'} (officially out
 * to the supplier). Emitted from both:
 *
 * <ul>
 *   <li>The shortage-driven auto-PR path when
 *       {@code northwood.purchasing.shortagePoAutoApprove=true} (default) —
 *       fires immediately after {@link PurchaseOrderCreated} in the same txn.</li>
 *   <li>The manual approval path when a human calls
 *       {@code POST /api/purchase-orders/{id}/approve} on a PO sitting at
 *       {@code 'draft'}.</li>
 * </ul>
 *
 * <p>The purchase-to-pay saga moves from {@code purchase_order_approved →
 * waiting_for_goods} after this event lands; the worker holds the saga at
 * {@code 'started'} until the PO transitions out of {@code 'draft'}.
 */
public record PurchaseOrderApproved(
    UUID eventId,
    UUID aggregateId,             // purchase_order_header_id
    String purchaseOrderNumber,
    UUID supplierId,
    String currencyCode,
    BigDecimal totalAmount,
    String approver,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.PurchaseOrderApproved";

    @Override public String eventType() { return EVENT_TYPE; }
}
