package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import java.util.UUID;
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
        // §2.37 Slice 4: the production-planning board's sales_order_header_id is
        // the sales order this WO ultimately serves. Make-to-order (salesOrderHeaderId)
        // was retired in Slice 3, so the link now comes from sourceSalesOrderHeaderId
        // (the SO whose shortage triggered this make-to-stock replenishment WO).
        // Coalesce so any residual make-to-order WO still maps correctly.
        UUID salesOrderHeaderId = payload.salesOrderHeaderId() != null
            ? payload.salesOrderHeaderId()
            : payload.sourceSalesOrderHeaderId();
        projection.createFromWorkOrder(
            payload.aggregateId(),
            payload.workOrderNumber(),
            salesOrderHeaderId,
            payload.finishedProductId(),
            payload.finishedProductSku(),
            payload.finishedProductName(),
            payload.plannedQuantity(),
            payload.occurredAt()
        );
    }
}
