package com.northwood.sales.application.inbox;

import com.northwood.manufacturing.domain.events.WorkOrderCreated;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.WorkOrderCreated}. Parses the
 * payload and asks the manager to register the WO id (sub-assembly children
 * with non-null {@code parentWorkOrderId} are no-ops at the sales level).
 */
@Component
public class WorkOrderCreatedHandler extends AbstractInboxHandler<WorkOrderCreated> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.work-order-created";

    private final SalesOrderFulfilmentSagaManager sagaManager;

    public WorkOrderCreatedHandler(
        InboxPort inbox, SalesOrderFulfilmentSagaManager sagaManager, ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCreated.class, WorkOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
    }

    @Override
    protected void apply(WorkOrderCreated payload, EventEnvelope envelope) {
        sagaManager.applyWorkOrderCreated(
            payload.salesOrderHeaderId(), payload.aggregateId(), payload.parentWorkOrderId()
        );
    }
}
