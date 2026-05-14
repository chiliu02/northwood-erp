package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class SalesOrderPlacedHandler extends AbstractInboxHandler<SalesOrderPlaced> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.order-placed";

    private final SalesOrder360Projection projection;

    public SalesOrderPlacedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderPlaced.class, SalesOrderPlaced.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderPlaced payload, EventEnvelope envelope) {
        java.time.LocalDate orderDate = payload.occurredAt() == null
            ? java.time.LocalDate.now()
            : payload.occurredAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        projection.createFromOrder(
            payload.aggregateId(),
            payload.orderNumber(),
            payload.customerId(),
            payload.customerName(),
            orderDate,
            null,  // requested_delivery_date not carried on the event
            payload.currencyCode(),
            payload.totalAmount(),
            payload.occurredAt(),
            SalesOrderPlaced.EVENT_TYPE,
            envelope.actorUserId()
        );
    }
}
