package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.reporting.application.inbox.PurchaseOrderTrackingProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class SupplierInvoiceApprovedHandler extends AbstractInboxHandler<SupplierInvoiceApproved> {

    public static final String HANDLER_NAME = "reporting.po-tracking.invoice-approved";

    private final PurchaseOrderTrackingProjection projection;

    public SupplierInvoiceApprovedHandler(
        InboxPort inbox,
        PurchaseOrderTrackingProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierInvoiceApproved.class, SupplierInvoiceApproved.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SupplierInvoiceApproved payload, EventEnvelope envelope) {
        projection.recordInvoiceApproved(
            payload.purchaseOrderHeaderId(),
            payload.aggregateId(),
            payload.totalAmount(),
            payload.occurredAt(),
            envelope.actorUserId()
        );
    }
}
