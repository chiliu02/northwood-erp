package com.northwood.reporting.application.inbox;

import com.northwood.purchasing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice F: reporting consumer of
 * {@code purchasing.ReplenishmentDispatched}. Flips the
 * {@code reporting.replenishment_history_view} row to {@code 'dispatched'}
 * and records the purchase-requisition id.
 *
 * <p>Sibling of {@link ManufacturingReplenishmentDispatchedHandler}; both
 * events live in different Java packages and carry distinct
 * {@code EVENT_TYPE} strings, so the registration / dispatcher routing is
 * unambiguous despite the shared class name.
 */
@Component("reporting_replenishment_pur_dispatched")
public class PurchasingReplenishmentDispatchedHandler
    extends AbstractInboxHandler<ReplenishmentDispatched> {

    public static final String CONSUMER_NAME = "reporting.replenishment-history.pur-dispatched";

    private final ReplenishmentHistoryProjection projection;

    public PurchasingReplenishmentDispatchedHandler(
        InboxPort inbox,
        ReplenishmentHistoryProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentDispatched.class, ReplenishmentDispatched.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ReplenishmentDispatched payload, EventEnvelope envelope) {
        projection.recordDispatched(
            payload.replenishmentRequestId(),
            "purchase_requisition",
            payload.aggregateId(),
            payload.occurredAt()
        );

        log.info("[{}] applied {} ({}) for replenishment_request={} → purchase_requisition={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.replenishmentRequestId(), payload.aggregateId());
    }
}
