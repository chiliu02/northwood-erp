package com.northwood.reporting.application.inbox.atp;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("atp_WorkOrderCreatedHandler")
public class WorkOrderCreatedHandler extends AbstractInboxHandler<WorkOrderCreated> {

    public static final String HANDLER_NAME = "reporting.atp.work-order-created";

    private final AvailableToPromiseProjection projection;

    public WorkOrderCreatedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCreated.class, WorkOrderCreated.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderCreated payload, EventEnvelope envelope) {
        projection.recordWorkOrderPlanned(
            payload.finishedProductId(),
            payload.finishedProductSku(),
            payload.finishedProductName(),
            payload.plannedQuantity(),
            payload.occurredAt()
        );
    }
}
