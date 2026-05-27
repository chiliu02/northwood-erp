package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;

import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.sales.application.SalesOrderReadyToShipEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.WorkOrderManufacturingCompleted}.
 * Parses the payload and asks the manager to record the WO completion;
 * sub-assembly child WOs (non-null parent) are no-ops. When the final WO
 * completes and the manager transitions the saga to {@code ready_to_ship},
 * emits {@code sales.SalesOrderReadyToShip} so reporting can advance
 * {@code order_status} (the shipment picker's filter).
 */
@Component
public class WorkOrderManufacturingCompletedHandler extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.work-order-completed";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderReadyToShipEmitter readyToShipEmitter;

    public WorkOrderManufacturingCompletedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderReadyToShipEmitter readyToShipEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderManufacturingCompleted.class, WorkOrderManufacturingCompleted.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.readyToShipEmitter = readyToShipEmitter;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        String newState = sagaManager.applyWorkOrderManufacturingCompleted(
            payload.salesOrderHeaderId(), payload.aggregateId(), payload.parentWorkOrderId()
        );
        if (READY_TO_SHIP.equals(newState)) {
            readyToShipEmitter.emitReadyToShip(payload.salesOrderHeaderId());
        }
    }
}
