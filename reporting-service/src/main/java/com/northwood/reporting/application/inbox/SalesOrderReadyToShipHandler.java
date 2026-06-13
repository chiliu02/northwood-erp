package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.domain.events.SalesOrderReadyToShip;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

/**
 * Reporting handler for {@code sales.SalesOrderReadyToShip}: advances the
 * {@code sales_order_360_view.order_status} to the sales header fold ladder's
 * {@code 'reserved'} rung (every line reserved) so the shipment UI's order picker
 * (which surfaces {@code reserved} / {@code partially_shipped} orders) lists it
 * as shippable.
 */
@Component
public class SalesOrderReadyToShipHandler extends AbstractInboxHandler<SalesOrderReadyToShip> {

    public static final String HANDLER_NAME = "reporting.sales-order-360.ready-to-ship";

    private final SalesOrder360Projection projection;

    public SalesOrderReadyToShipHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderReadyToShip.class, SalesOrderReadyToShip.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderReadyToShip payload, EventEnvelope envelope) {
        projection.recordReadyToShip(payload.aggregateId(), payload.occurredAt(), envelope.actorUserId());
    }
}
