package com.northwood.sales.application.inbox;

import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.WorkOrderManufacturingCompleted}.
 * Parses the payload and asks the manager to record the WO completion;
 * sub-assembly child WOs (non-null parent) are no-ops.
 */
@Component
public class WorkOrderManufacturingCompletedHandler extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.work-order-completed";

    private final SalesOrderFulfilmentSagaManager sagaManager;

    public WorkOrderManufacturingCompletedHandler(
        InboxPort inbox, SalesOrderFulfilmentSagaManager sagaManager, ObjectMapper json
    ) {
        super(inbox, json, WorkOrderManufacturingCompleted.class, WorkOrderManufacturingCompleted.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        sagaManager.applyWorkOrderManufacturingCompleted(
            payload.salesOrderHeaderId(), payload.aggregateId(), payload.parentWorkOrderId()
        );
    }
}
