package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class WorkOrderManufacturingCompletedHandler
    extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.manufacturing-completed";

    private final SalesOrder360Projection projection;

    public WorkOrderManufacturingCompletedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderManufacturingCompleted.class, WorkOrderManufacturingCompleted.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        if (payload.parentWorkOrderId() != null) {
            // Sub-assembly child — only the top-level WO completion advances
            // the order's manufacturing_status.
            log.debug("[{}] skipping sub-assembly WO {} (parent={})",
                CONSUMER_NAME, payload.aggregateId(), payload.parentWorkOrderId());
            return;
        }
        if (payload.salesOrderHeaderId() == null) {
            log.debug("[{}] WO {} has no sales-order link; not part of any 360 view",
                CONSUMER_NAME, payload.aggregateId());
            return;
        }
        projection.recordManufacturingCompleted(payload.salesOrderHeaderId(), payload.occurredAt(), envelope.actorUserId());
    }
}
