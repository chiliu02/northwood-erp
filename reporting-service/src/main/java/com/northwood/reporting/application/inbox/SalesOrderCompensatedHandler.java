package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.sales.domain.events.SalesOrderCompensated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

/**
 * Reporting handler for {@code sales.SalesOrderCompensated}: flips the
 * {@code sales_order_360_view.order_status} to {@code 'cancelled'} so the UI
 * surfaces the cancellation as the order's terminal state.
 */
@Component
public class SalesOrderCompensatedHandler extends AbstractInboxHandler<SalesOrderCompensated> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.compensated";

    private final SalesOrder360Projection projection;

    public SalesOrderCompensatedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCompensated.class, SalesOrderCompensated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderCompensated payload, EventEnvelope envelope) {
        projection.recordCancellation(payload.aggregateId(), payload.occurredAt(), envelope.actorUserId());
    }
}
