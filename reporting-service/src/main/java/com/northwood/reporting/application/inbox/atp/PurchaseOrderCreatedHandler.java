package com.northwood.reporting.application.inbox.atp;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("atp_PurchaseOrderCreatedHandler")
public class PurchaseOrderCreatedHandler extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String HANDLER_NAME = "reporting.atp.po-created";

    private final AvailableToPromiseProjection projection;

    public PurchaseOrderCreatedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCreated.class, PurchaseOrderCreated.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(PurchaseOrderCreated payload, EventEnvelope envelope) {
        if (payload.lines() == null) return;
        for (var l : payload.lines()) {
            projection.recordPurchaseOrderLine(
                l.productId(), l.productSku(), l.productName(),
                l.orderedQuantity(), payload.occurredAt());
        }
    }
}
