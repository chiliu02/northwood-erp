package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.sales.domain.events.SalesOrderLineQuantityChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.SalesOrderLineQuantityChanged} (§1G
 * line amendment). Delta-adjusts the line's reservation — reserves the increase
 * (raising a replenishment if short) or releases the decrease. A pure price
 * change (unchanged quantity) is a no-op delta. No-op if the order has no live
 * reservation for that line.
 */
@Component
public class SalesOrderLineQuantityChangedHandler extends AbstractInboxHandler<SalesOrderLineQuantityChanged> {

    public static final String CONSUMER_NAME = "inventory.sales-order-line-quantity-changed";

    private final StockReservationService reservation;

    public SalesOrderLineQuantityChangedHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderLineQuantityChanged.class, SalesOrderLineQuantityChanged.EVENT_TYPE, CONSUMER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(SalesOrderLineQuantityChanged payload, EventEnvelope envelope) {
        reservation.applyLineQuantityChanged(payload.aggregateId(), payload.salesOrderLineId(), payload.newQuantity());
        log.info("[{}] processed {} ({}) for sales_order={} line={} newQty={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.salesOrderLineId(), payload.newQuantity());
    }
}
