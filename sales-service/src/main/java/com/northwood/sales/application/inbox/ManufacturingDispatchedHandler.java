package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_FAILED;

import com.northwood.manufacturing.domain.events.ManufacturingDispatched;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.ManufacturingDispatched}. Counts
 * accepted lines, asks the manager to apply the dispatch outcome, and (if
 * the manager flips the saga to {@code 'stock_reservation_failed'}) projects
 * the order header to {@code 'rejected'}.
 */
@Component
public class ManufacturingDispatchedHandler extends AbstractInboxHandler<ManufacturingDispatched> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.manufacturing-dispatched";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;

    public ManufacturingDispatchedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        ObjectMapper json
    ) {
        super(inbox, json, ManufacturingDispatched.class, ManufacturingDispatched.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
    }

    @Override
    protected void apply(ManufacturingDispatched payload, EventEnvelope envelope) {
        int acceptedCount = (int) payload.lines().stream()
            .filter(l -> "accepted".equals(l.outcome()))
            .count();
        int totalLines = payload.lines().size();

        String newState = sagaManager.applyManufacturingDispatched(
            payload.salesOrderHeaderId(), acceptedCount, totalLines
        );
        if (STOCK_RESERVATION_FAILED.equals(newState)) {
            statusProjection.markStatus(payload.salesOrderHeaderId(), SalesOrder.REJECTED);
        }
    }
}
