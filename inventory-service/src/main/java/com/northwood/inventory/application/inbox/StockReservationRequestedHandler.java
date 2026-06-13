package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.StockReservationRequested}.
 * Delegates the actual reservation to {@link StockReservationService}, which
 * performs the {@code stock_balance} mutation, persists the reservation, and
 * emits {@code inventory.StockReserved} via the outbox — all in the same
 * transaction.
 */
@Component
public class StockReservationRequestedHandler extends AbstractInboxHandler<StockReservationRequested> {

    public static final String HANDLER_NAME = "inventory.stock-reservation";

    private final StockReservationService reservation;

    public StockReservationRequestedHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, StockReservationRequested.class, StockReservationRequested.EVENT_TYPE, HANDLER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(StockReservationRequested payload, EventEnvelope envelope) {
        reservation.reserveForSalesOrder(payload);

        log.info("[{}] processed {} ({}) for sales_order={}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(), payload.salesOrderId());
    }
}
