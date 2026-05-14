package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.reporting.application.inbox.PurchaseOrderTrackingProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class SupplierPaymentMadeHandler extends AbstractInboxHandler<SupplierPaymentMade> {

    public static final String CONSUMER_NAME = "reporting.po-tracking.supplier-payment-made";

    private final PurchaseOrderTrackingProjection projection;
    private final ProductionPlanningProjection planning;

    public SupplierPaymentMadeHandler(
        InboxPort inbox,
        PurchaseOrderTrackingProjection projection,
        ProductionPlanningProjection planning,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierPaymentMade.class, SupplierPaymentMade.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
        this.planning = planning;
    }

    @Override
    protected void apply(SupplierPaymentMade payload, EventEnvelope envelope) {
        projection.recordPayment(
            payload.purchaseOrderHeaderId(),
            payload.aggregateId(),
            payload.allocatedAmount(),
            payload.invoiceStatusAfter(),
            payload.occurredAt(),
            envelope.actorUserId()
        );
        // §2.1: when a PO transitions to 'paid', it stops counting toward the
        // source WO's open_purchase_orders_count. Recompute from the
        // just-updated tracking row.
        projection.findSourceWorkOrderForPo(payload.purchaseOrderHeaderId())
            .ifPresent(woId -> {
                int count = projection.countOpenForWorkOrder(woId);
                planning.setOpenPoCount(woId, count, payload.occurredAt());
            });
    }
}
