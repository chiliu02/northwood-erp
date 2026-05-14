package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class ShipmentPostedHandler extends AbstractInboxHandler<ShipmentPosted> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.shipment-posted";

    private final SalesOrder360Projection projection;

    public ShipmentPostedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ShipmentPosted.class, ShipmentPosted.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ShipmentPosted payload, EventEnvelope envelope) {
        projection.recordShipment(payload.salesOrderHeaderId(), payload.occurredAt(), envelope.actorUserId());
    }
}
