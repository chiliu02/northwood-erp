package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class WorkOrderCreatedHandler extends AbstractInboxHandler<WorkOrderCreated> {

    public static final String CONSUMER_NAME = "reporting.production-planning.work-order-created";

    private final ProductionPlanningProjection projection;

    public WorkOrderCreatedHandler(
        InboxPort inbox,
        ProductionPlanningProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCreated.class, WorkOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderCreated payload, EventEnvelope envelope) {
        projection.createFromWorkOrder(
            payload.aggregateId(),
            payload.workOrderNumber(),
            payload.salesOrderHeaderId(),
            payload.finishedProductId(),
            payload.finishedProductSku(),
            payload.finishedProductName(),
            payload.plannedQuantity(),
            payload.occurredAt()
        );
    }
}
