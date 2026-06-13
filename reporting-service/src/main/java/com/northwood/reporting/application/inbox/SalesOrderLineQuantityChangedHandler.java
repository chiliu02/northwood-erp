package com.northwood.reporting.application.inbox;

import com.northwood.sales.domain.events.SalesOrderLineQuantityChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code sales.SalesOrderLineQuantityChanged}.
 * Refreshes the 360 header money: a quantity (or price) change moved the order
 * total, so overwrite {@code total_amount} / {@code outstanding_amount} with the
 * post-amendment figure the event carries.
 */
@Component
public class SalesOrderLineQuantityChangedHandler extends AbstractInboxHandler<SalesOrderLineQuantityChanged> {

    public static final String HANDLER_NAME = "reporting.sales-order-360.line-quantity-changed";

    private final SalesOrder360Projection projection;

    public SalesOrderLineQuantityChangedHandler(InboxPort inbox, SalesOrder360Projection projection, ObjectMapper json) {
        super(inbox, json, SalesOrderLineQuantityChanged.class, SalesOrderLineQuantityChanged.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderLineQuantityChanged payload, EventEnvelope envelope) {
        projection.recordAmendedTotal(
            payload.aggregateId(), payload.newOrderTotal(),
            payload.occurredAt(), SalesOrderLineQuantityChanged.EVENT_TYPE, envelope.actorUserId());
    }
}
