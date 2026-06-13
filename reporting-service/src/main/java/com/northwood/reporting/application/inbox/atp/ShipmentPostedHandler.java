package com.northwood.reporting.application.inbox.atp;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("atp_ShipmentPostedHandler")
public class ShipmentPostedHandler extends AbstractInboxHandler<ShipmentPosted> {

    public static final String HANDLER_NAME = "reporting.atp.shipment-posted";

    private final AvailableToPromiseProjection projection;

    public ShipmentPostedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ShipmentPosted.class, ShipmentPosted.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ShipmentPosted payload, EventEnvelope envelope) {
        if (payload.lines() == null) return;
        for (var l : payload.lines()) {
            projection.recordShippedLine(
                l.productId(), l.productSku(), l.productName(),
                l.shippedQuantity(), payload.occurredAt());
        }
    }
}
