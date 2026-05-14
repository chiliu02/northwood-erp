package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

/**
 * Top-level WOs only — sub-assembly children inflate the count
 * misleadingly (one customer-driven order can cascade to several internal
 * WOs). Filters on {@code parentWorkOrderId == null}.
 */
@Component("dashboard_WorkOrderCreatedHandler")
public class WorkOrderCreatedHandler extends AbstractInboxHandler<WorkOrderCreated> {

    public static final String CONSUMER_NAME = "reporting.dashboard.work-order-created";

    private final FinancialDashboardProjection projection;

    public WorkOrderCreatedHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCreated.class, WorkOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderCreated payload, EventEnvelope envelope) {
        if (payload.parentWorkOrderId() != null) return;
        projection.recordWorkOrderCreated(null, payload.occurredAt());
    }
}
