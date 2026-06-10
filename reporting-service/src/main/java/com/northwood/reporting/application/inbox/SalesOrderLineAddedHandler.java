package com.northwood.reporting.application.inbox;

import com.northwood.sales.domain.events.SalesOrderLineAdded;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code sales.SalesOrderLineAdded}. Refreshes the
 * 360 header money: the added line changed the order total, so overwrite
 * {@code total_amount} / {@code outstanding_amount} with the post-amendment
 * figure the event carries. (Inventory consumes the same event to reserve the
 * new line; this handler is the reporting read-model half.)
 */
@Component
public class SalesOrderLineAddedHandler extends AbstractInboxHandler<SalesOrderLineAdded> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.line-added";

    private final SalesOrder360Projection projection;

    public SalesOrderLineAddedHandler(InboxPort inbox, SalesOrder360Projection projection, ObjectMapper json) {
        super(inbox, json, SalesOrderLineAdded.class, SalesOrderLineAdded.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderLineAdded payload, EventEnvelope envelope) {
        projection.recordAmendedTotal(
            payload.aggregateId(), payload.newOrderTotal(),
            payload.occurredAt(), SalesOrderLineAdded.EVENT_TYPE, envelope.actorUserId());
    }
}
