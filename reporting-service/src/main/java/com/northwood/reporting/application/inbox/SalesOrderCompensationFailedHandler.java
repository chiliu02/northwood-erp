package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.sales.domain.events.SalesOrderCompensationFailed;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

/**
 * Reporting handler for {@code sales.SalesOrderCompensationFailed}: flips the
 * {@code sales_order_360_view.order_status} to {@code 'cancelled'}. The order was
 * cancelled cleanly even though an order-pegged supply leg could not be withdrawn —
 * the residue is an ops concern (RMA / write-off), not a different order status — so
 * the 360 mirrors {@link SalesOrderCompensatedHandler} and shows the cancellation as
 * the terminal state.
 */
@Component
public class SalesOrderCompensationFailedHandler extends AbstractInboxHandler<SalesOrderCompensationFailed> {

    public static final String HANDLER_NAME = "reporting.sales-order-360.compensation-failed";

    private final SalesOrder360Projection projection;

    public SalesOrderCompensationFailedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCompensationFailed.class, SalesOrderCompensationFailed.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderCompensationFailed payload, EventEnvelope envelope) {
        projection.recordCancellation(payload.aggregateId(), payload.occurredAt(), envelope.actorUserId());
    }
}
