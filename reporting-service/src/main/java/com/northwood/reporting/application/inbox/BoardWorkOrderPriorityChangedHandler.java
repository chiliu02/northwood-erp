package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.WorkOrderPriorityChanged;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

/**
 * Production-planning consumer of
 * {@code manufacturing.WorkOrderPriorityChanged}. Updates
 * {@code production_planning_board.priority}.
 */
@Component
public class BoardWorkOrderPriorityChangedHandler
    extends AbstractInboxHandler<WorkOrderPriorityChanged> {

    public static final String HANDLER_NAME = "reporting.production-planning.work-order-priority-changed";

    private final ProductionPlanningProjection projection;

    public BoardWorkOrderPriorityChangedHandler(
        InboxPort inbox,
        ProductionPlanningProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderPriorityChanged.class, WorkOrderPriorityChanged.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderPriorityChanged payload, EventEnvelope envelope) {
        projection.recordPriorityChanged(
            payload.aggregateId(), payload.priority(), payload.occurredAt());
    }
}
