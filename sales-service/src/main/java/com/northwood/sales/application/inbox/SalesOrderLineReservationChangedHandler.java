package com.northwood.sales.application.inbox;

import com.northwood.inventory.domain.events.SalesOrderLineReservationChanged;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sales-side fan-in for {@code inventory.SalesOrderLineReservationChanged} — the
 * per-line reply to a line amendment. Delegates to
 * {@link SalesOrderFulfilmentSagaManager#applyLineReservationChanged} which
 * reconciles the saga's outstanding-replenishment set: a short amended line
 * (shortage &gt; 0) registers as outstanding and parks the saga at
 * {@code stock_reservation_incomplete}; a fully-reserved or released line drops
 * from the set and un-parks the saga to {@code ready_to_ship} once the set
 * empties.
 */
@Component
public class SalesOrderLineReservationChangedHandler extends AbstractInboxHandler<SalesOrderLineReservationChanged> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.line-reservation-changed";

    private final SalesOrderFulfilmentSagaManager sagaManager;

    public SalesOrderLineReservationChangedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        ObjectMapper json
    ) {
        super(inbox, json,
            SalesOrderLineReservationChanged.class,
            SalesOrderLineReservationChanged.EVENT_TYPE,
            CONSUMER_NAME);
        this.sagaManager = sagaManager;
    }

    @Override
    protected void apply(SalesOrderLineReservationChanged payload, EventEnvelope envelope) {
        boolean lineIsShort = payload.shortageQuantity().signum() > 0;
        String state = sagaManager.applyLineReservationChanged(
            payload.aggregateId(), payload.salesOrderLineId(), lineIsShort);
        log.info("[{}] processed {} ({}) for sales_order={} line={} short={} → saga {}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.salesOrderLineId(), lineIsShort, state);
    }
}
