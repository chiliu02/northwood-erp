package com.northwood.reporting.application.inbox.shortage;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.MaterialShortageProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.purchasing.domain.events.PurchaseRequisitionCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("shortage_PurchaseRequisitionCreatedHandler")
public class PurchaseRequisitionCreatedHandler extends AbstractInboxHandler<PurchaseRequisitionCreated> {

    public static final String CONSUMER_NAME = "reporting.material-shortage.pr-created";

    private final MaterialShortageProjection projection;

    public PurchaseRequisitionCreatedHandler(
        InboxPort inbox,
        MaterialShortageProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseRequisitionCreated.class, PurchaseRequisitionCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(PurchaseRequisitionCreated payload, EventEnvelope envelope) {
        // Only shortage-driven PRs flip the projection's status; manual PRs
        // for other operational reasons aren't part of this view.
        if (!PurchaseRequisitionCreated.SOURCE_TYPE_WORK_ORDER_SHORTAGE.equals(payload.sourceType())) return;
        if (payload.lines() == null) return;
        for (var l : payload.lines()) {
            projection.recordRequisitionLine(
                l.productId(),
                l.productSku(),
                l.productName(),
                payload.occurredAt()
            );
        }
    }
}
