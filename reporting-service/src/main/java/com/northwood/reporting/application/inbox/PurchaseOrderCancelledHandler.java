package com.northwood.reporting.application.inbox;

import com.northwood.purchasing.domain.events.PurchaseOrderCancelled;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * PO-tracking consumer of {@code purchasing.PurchaseOrderCancelled}. Flips
 * {@code reporting.purchase_order_tracking_view.po_status} to {@code 'cancelled'}
 * when a draft PO is rejected via {@code POST /api/purchase-orders/{id}/reject},
 * so the rejected PO drops out of the open-PO counts.
 */
@Component
public class PurchaseOrderCancelledHandler extends AbstractInboxHandler<PurchaseOrderCancelled> {

    public static final String HANDLER_NAME = "reporting.po-tracking.po-cancelled";

    private final PurchaseOrderTrackingProjection projection;

    public PurchaseOrderCancelledHandler(
        InboxPort inbox,
        PurchaseOrderTrackingProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCancelled.class, PurchaseOrderCancelled.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(PurchaseOrderCancelled payload, EventEnvelope envelope) {
        projection.recordPoCancelled(payload.aggregateId(), payload.occurredAt(), envelope.actorUserId());

        log.info("[{}] applied {} ({}) for po={} (by={})",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.cancelledBy());
    }
}
