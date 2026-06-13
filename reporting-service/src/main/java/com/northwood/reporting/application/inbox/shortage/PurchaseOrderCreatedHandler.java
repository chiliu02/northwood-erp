package com.northwood.reporting.application.inbox.shortage;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.MaterialShortageProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("shortage_PurchaseOrderCreatedHandler")
public class PurchaseOrderCreatedHandler extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String HANDLER_NAME = "reporting.material-shortage.po-created";

    private final MaterialShortageProjection projection;

    public PurchaseOrderCreatedHandler(
        InboxPort inbox,
        MaterialShortageProjection projection,
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
                l.productId(),
                l.productSku(),
                l.productName(),
                l.orderedQuantity(),
                payload.occurredAt()
            );
        }
    }
}
