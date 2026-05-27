package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.domain.events.SalesOrderReadyToShip;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

/**
 * Reporting handler for {@code sales.SalesOrderReadyToShip}: advances the
 * {@code sales_order_360_view.order_status} to {@code 'ready_to_ship'} so the
 * shipment UI's order picker (which filters on {@code orderStatus ===
 * "ready_to_ship"}) surfaces the order as shippable.
 */
@Component
public class SalesOrderReadyToShipHandler extends AbstractInboxHandler<SalesOrderReadyToShip> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.ready-to-ship";

    private final SalesOrder360Projection projection;

    public SalesOrderReadyToShipHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderReadyToShip.class, SalesOrderReadyToShip.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderReadyToShip payload, EventEnvelope envelope) {
        projection.recordReadyToShip(payload.aggregateId(), payload.occurredAt(), envelope.actorUserId());
    }
}
