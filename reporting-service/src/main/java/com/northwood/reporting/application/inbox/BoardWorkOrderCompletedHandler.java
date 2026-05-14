package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

/**
 * Production-planning consumer of {@code manufacturing.WorkOrderManufacturingCompleted}.
 * Distinct from {@link WorkOrderManufacturingCompletedHandler} which feeds
 * the sales-360 projection — same event, different consumer_name and
 * different downstream projection.
 */
@Component
public class BoardWorkOrderCompletedHandler extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String CONSUMER_NAME = "reporting.production-planning.work-order-completed";

    private final ProductionPlanningProjection projection;

    public BoardWorkOrderCompletedHandler(
        InboxPort inbox,
        ProductionPlanningProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderManufacturingCompleted.class, WorkOrderManufacturingCompleted.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        projection.recordWorkOrderCompleted(
            payload.aggregateId(),
            payload.completedQuantity(),
            payload.occurredAt()
        );
    }
}
