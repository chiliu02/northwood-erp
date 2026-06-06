package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.sales.domain.events.SalesOrderLineAdded;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.SalesOrderLineAdded} (§1G line
 * amendment). Reserves the newly-added line against the order's existing
 * reservation (no-op if the order isn't reserved yet — the line is then covered
 * by the initial/retried whole-order reservation).
 */
@Component
public class SalesOrderLineAddedHandler extends AbstractInboxHandler<SalesOrderLineAdded> {

    public static final String CONSUMER_NAME = "inventory.sales-order-line-added";

    private final StockReservationService reservation;

    public SalesOrderLineAddedHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderLineAdded.class, SalesOrderLineAdded.EVENT_TYPE, CONSUMER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(SalesOrderLineAdded payload, EventEnvelope envelope) {
        reservation.applyLineAdded(
            payload.aggregateId(), payload.salesOrderLineId(), payload.productId(),
            payload.productSku(), payload.productName(), payload.orderedQuantity()
        );
        log.info("[{}] processed {} ({}) for sales_order={} line={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId(), payload.salesOrderLineId());
    }
}
