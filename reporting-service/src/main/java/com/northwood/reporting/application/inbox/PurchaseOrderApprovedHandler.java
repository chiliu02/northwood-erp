package com.northwood.reporting.application.inbox;

import com.northwood.purchasing.domain.events.PurchaseOrderApproved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * PO-tracking consumer of {@code purchasing.PurchaseOrderApproved}.
 * Flips {@code reporting.purchase_order_tracking_view.po_status} from
 * {@code 'draft'} to {@code 'sent'} and stamps {@code approved_at} when a
 * draft PO is manually approved via {@code POST /api/purchase-orders/{id}/approve}.
 *
 * <p>The shortage-driven auto-approve path emits {@code PurchaseOrderApproved}
 * in the same transaction as {@code PurchaseOrderCreated}; the existing
 * {@code reporting.po-tracking.po-created} handler already stores the
 * post-approval status in that case, so this handler is the redundant-but-
 * idempotent finisher there. The manual approval path was the actual gap:
 * before this handler, {@code po_status} stayed at {@code 'draft'} forever
 * after manual approval.
 */
@Component
public class PurchaseOrderApprovedHandler extends AbstractInboxHandler<PurchaseOrderApproved> {

    public static final String CONSUMER_NAME = "reporting.po-tracking.po-approved";

    private final PurchaseOrderTrackingProjection projection;

    public PurchaseOrderApprovedHandler(
        InboxPort inbox,
        PurchaseOrderTrackingProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderApproved.class, PurchaseOrderApproved.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(PurchaseOrderApproved payload, EventEnvelope envelope) {
        projection.recordPoApproved(payload.aggregateId(), payload.occurredAt(), envelope.actorUserId());

        log.info("[{}] applied {} ({}) for po={} (approver={})",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.approver());
    }
}
