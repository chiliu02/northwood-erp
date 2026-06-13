package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.OperationCompleted;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class OperationCompletedHandler extends AbstractInboxHandler<OperationCompleted> {

    public static final String HANDLER_NAME = "reporting.production-planning.operation-completed";

    private final ProductionPlanningProjection projection;

    public OperationCompletedHandler(
        InboxPort inbox,
        ProductionPlanningProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, OperationCompleted.class, OperationCompleted.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(OperationCompleted payload, EventEnvelope envelope) {
        projection.recordOperationCompleted(payload.aggregateId(), payload.occurredAt());
    }
}
