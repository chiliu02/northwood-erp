package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;

import com.northwood.manufacturing.domain.events.SalesOrderCancellationApplied;
import com.northwood.sales.application.SalesOrderCompensationEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.SalesOrderCancellationApplied}.
 * Mirror of {@link InventoryCancellationAppliedHandler} for manufacturing's
 * ack. Trusts the ack as a boolean signal; the {@code workOrdersCancelled}
 * count is informational.
 */
@Component
public class ManufacturingCancellationAppliedHandler extends AbstractInboxHandler<SalesOrderCancellationApplied> {

    public static final String CONSUMER_NAME = "sales.compensation-manufacturing-ack";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderCompensationEmitter compensationEmitter;

    public ManufacturingCancellationAppliedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderCompensationEmitter compensationEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCancellationApplied.class, SalesOrderCancellationApplied.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.compensationEmitter = compensationEmitter;
    }

    @Override
    protected void apply(SalesOrderCancellationApplied payload, EventEnvelope envelope) {
        String newState = sagaManager.applyManufacturingCancellationApplied(payload.aggregateId());
        if (COMPENSATED.equals(newState)) {
            compensationEmitter.emitCompensated(payload.aggregateId());
        }

        log.info("[{}] processed {} ({}) for sales_order={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
