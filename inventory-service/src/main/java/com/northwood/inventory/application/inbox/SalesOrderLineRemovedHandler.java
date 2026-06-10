package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.sales.domain.events.SalesOrderLineRemoved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.SalesOrderLineRemoved} (line
 * amendment). Releases the removed line's reservation back to the free pool
 * (no-op if the order has no live reservation for that line).
 */
@Component
public class SalesOrderLineRemovedHandler extends AbstractInboxHandler<SalesOrderLineRemoved> {

    public static final String CONSUMER_NAME = "inventory.sales-order-line-removed";

    private final StockReservationService reservation;

    public SalesOrderLineRemovedHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderLineRemoved.class, SalesOrderLineRemoved.EVENT_TYPE, CONSUMER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(SalesOrderLineRemoved payload, EventEnvelope envelope) {
        reservation.applyLineRemoved(payload.aggregateId(), payload.salesOrderLineId());
        log.info("[{}] processed {} ({}) for sales_order={} line={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId(), payload.salesOrderLineId());
    }
}
