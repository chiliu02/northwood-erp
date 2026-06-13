package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code sales.SalesOrderCancellationRequested}: releases the
 * stock reservation for this order (if any) and emits
 * {@code inventory.SalesOrderCancellationApplied} so the sales fulfilment saga
 * can advance from {@code compensating} to {@code compensated}.
 */
@Component
public class SalesOrderCancellationRequestedHandler extends AbstractInboxHandler<SalesOrderCancellationRequested> {

    public static final String HANDLER_NAME = "inventory.sales-cancel";

    private final StockReservationService reservation;

    public SalesOrderCancellationRequestedHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCancellationRequested.class, SalesOrderCancellationRequested.EVENT_TYPE, HANDLER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(SalesOrderCancellationRequested payload, EventEnvelope envelope) {
        reservation.releaseForSalesOrder(payload.aggregateId());

        log.info("[{}] processed {} ({}) for sales_order={}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
