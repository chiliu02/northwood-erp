package com.northwood.reporting.application.inbox.atp;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("atp_RawMaterialsReservedHandler")
public class RawMaterialsReservedHandler extends AbstractInboxHandler<RawMaterialsReserved> {

    public static final String CONSUMER_NAME = "reporting.atp.raw-materials-reserved";

    private final AvailableToPromiseProjection projection;

    public RawMaterialsReservedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialsReserved.class, RawMaterialsReserved.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(RawMaterialsReserved payload, EventEnvelope envelope) {
        if (payload.components() == null) return;
        for (var c : payload.components()) {
            projection.recordProductionReservation(
                c.componentProductId(), c.reservedQuantity(), payload.occurredAt());
        }
    }
}
