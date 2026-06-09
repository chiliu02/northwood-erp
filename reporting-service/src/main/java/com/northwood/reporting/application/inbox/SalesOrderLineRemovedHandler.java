package com.northwood.reporting.application.inbox;

import com.northwood.sales.domain.events.SalesOrderLineRemoved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code sales.SalesOrderLineRemoved} (§1G.3). Refreshes the
 * 360 header money: a removed (soft-cancelled) line drops out of the order
 * total, so overwrite {@code total_amount} / {@code outstanding_amount} with the
 * post-amendment figure the event carries.
 */
@Component
public class SalesOrderLineRemovedHandler extends AbstractInboxHandler<SalesOrderLineRemoved> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.line-removed";

    private final SalesOrder360Projection projection;

    public SalesOrderLineRemovedHandler(InboxPort inbox, SalesOrder360Projection projection, ObjectMapper json) {
        super(inbox, json, SalesOrderLineRemoved.class, SalesOrderLineRemoved.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderLineRemoved payload, EventEnvelope envelope) {
        projection.recordAmendedTotal(
            payload.aggregateId(), payload.newOrderTotal(),
            payload.occurredAt(), SalesOrderLineRemoved.EVENT_TYPE, envelope.actorUserId());
    }
}
