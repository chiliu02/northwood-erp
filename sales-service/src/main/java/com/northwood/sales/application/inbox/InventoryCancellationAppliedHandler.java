package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;

import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.sales.application.SalesOrderCompensationEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.SalesOrderCancellationApplied}. Asks
 * the manager to record inventory's compensation ack; if the manager flips
 * the saga to {@code 'compensated'} (i.e. manufacturing's ack had already
 * arrived), emits {@code sales.SalesOrderCompensated} via the shared
 * {@link SalesOrderCompensationEmitter}.
 */
@Component
public class InventoryCancellationAppliedHandler extends AbstractInboxHandler<InventorySalesOrderCancellationApplied> {

    public static final String HANDLER_NAME = "sales.compensation-inventory-ack";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderCompensationEmitter compensationEmitter;

    public InventoryCancellationAppliedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderCompensationEmitter compensationEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, InventorySalesOrderCancellationApplied.class, InventorySalesOrderCancellationApplied.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.compensationEmitter = compensationEmitter;
    }

    @Override
    protected void apply(InventorySalesOrderCancellationApplied payload, EventEnvelope envelope) {
        String newState = sagaManager.applyInventoryCancellationApplied(payload.aggregateId());
        if (COMPENSATED.equals(newState)) {
            compensationEmitter.emitCompensated(payload.aggregateId());
        }

        log.info("[{}] processed {} ({}) for sales_order={}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
