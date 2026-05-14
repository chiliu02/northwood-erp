package com.northwood.manufacturing.application.inbox;

import com.northwood.manufacturing.application.WorkOrderCancellationService;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code sales.SalesOrderCancellationRequested}: cancels
 * any active work orders for this sales order, force-flips associated
 * make-to-order sagas to {@code 'compensated'}, and emits
 * {@code manufacturing.SalesOrderCancellationApplied} so the sales fulfilment
 * saga can advance.
 */
@Component
public class SalesOrderCancellationRequestedHandler extends AbstractInboxHandler<SalesOrderCancellationRequested> {

    public static final String CONSUMER_NAME = "manufacturing.sales-cancel";

    private final WorkOrderCancellationService cancellation;

    public SalesOrderCancellationRequestedHandler(
        InboxPort inbox,
        WorkOrderCancellationService cancellation,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCancellationRequested.class, SalesOrderCancellationRequested.EVENT_TYPE, CONSUMER_NAME);
        this.cancellation = cancellation;
    }

    @Override
    protected void apply(SalesOrderCancellationRequested payload, EventEnvelope envelope) {
        cancellation.cancelForSalesOrder(payload.aggregateId(), payload.reason());

        log.info("[{}] processed {} ({}) for sales_order={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
