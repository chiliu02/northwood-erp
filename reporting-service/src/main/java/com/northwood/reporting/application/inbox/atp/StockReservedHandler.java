package com.northwood.reporting.application.inbox.atp;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("atp_StockReservedHandler")
public class StockReservedHandler extends AbstractInboxHandler<StockReserved> {

    public static final String CONSUMER_NAME = "reporting.atp.stock-reserved";

    private final AvailableToPromiseProjection projection;

    public StockReservedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, StockReserved.class, StockReserved.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(StockReserved payload, EventEnvelope envelope) {
        if (payload.lines() == null) return;
        for (var l : payload.lines()) {
            projection.recordSalesReservation(
                l.productId(), l.reservedQuantity(), payload.occurredAt());
        }
    }
}
