package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.WorkOrderCancelled;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

/**
 * §3.8: production-planning consumer of {@code manufacturing.WorkOrderCancelled}.
 * Flips {@code production_planning_board.work_order_status} to
 * {@code 'cancelled'}.
 */
@Component
public class BoardWorkOrderCancelledHandler extends AbstractInboxHandler<WorkOrderCancelled> {

    public static final String CONSUMER_NAME = "reporting.production-planning.work-order-cancelled";

    private final ProductionPlanningProjection projection;

    public BoardWorkOrderCancelledHandler(
        InboxPort inbox,
        ProductionPlanningProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCancelled.class, WorkOrderCancelled.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderCancelled payload, EventEnvelope envelope) {
        projection.recordWorkOrderCancelled(payload.aggregateId(), payload.occurredAt());
    }
}
