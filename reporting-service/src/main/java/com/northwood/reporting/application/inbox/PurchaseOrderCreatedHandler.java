package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.reporting.application.inbox.PurchaseOrderTrackingProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class PurchaseOrderCreatedHandler extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String CONSUMER_NAME = "reporting.po-tracking.po-created";

    private final PurchaseOrderTrackingProjection projection;
    private final ProductionPlanningProjection planning;

    public PurchaseOrderCreatedHandler(
        InboxPort inbox,
        PurchaseOrderTrackingProjection projection,
        ProductionPlanningProjection planning,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCreated.class, PurchaseOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
        this.planning = planning;
    }

    @Override
    protected void apply(PurchaseOrderCreated payload, EventEnvelope envelope) {
        projection.createFromPurchaseOrder(
            payload.aggregateId(),
            payload.purchaseOrderNumber(),
            payload.supplierId(),
            payload.supplierName(),
            payload.status(),
            payload.currencyCode(),
            payload.totalAmount(),
            payload.sourceWorkOrderId(),
            payload.occurredAt(),
            envelope.actorUserId()
        );
        // §2.1: shortage-driven POs increment open_purchase_orders_count on
        // the source WO's planning-board row. We use the payload field
        // directly (faster than a roundtrip to the just-written tracking
        // row, and it carries the same value).
        if (payload.sourceWorkOrderId() != null) {
            int count = projection.countOpenForWorkOrder(payload.sourceWorkOrderId());
            planning.setOpenPoCount(payload.sourceWorkOrderId(), count, payload.occurredAt());
        }
    }
}
