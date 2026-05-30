package com.northwood.reporting.application.inbox;

import com.northwood.manufacturing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice F: reporting consumer of
 * {@code manufacturing.ReplenishmentDispatched}. Flips the
 * {@code reporting.replenishment_history_view} row to {@code 'dispatched'}
 * and records the work-order id.
 *
 * <p>Sibling of {@link PurchasingReplenishmentDispatchedHandler}; both call
 * the same {@code recordDispatched} projection method with different
 * {@code dispatched_aggregate_kind} wire-format strings.
 */
@Component("reporting_replenishment_mfg_dispatched")
public class ManufacturingReplenishmentDispatchedHandler
    extends AbstractInboxHandler<ReplenishmentDispatched> {

    public static final String CONSUMER_NAME = "reporting.replenishment-history.mfg-dispatched";

    private final ReplenishmentHistoryProjection projection;

    public ManufacturingReplenishmentDispatchedHandler(
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
            "work_order",
            payload.aggregateId(),
            payload.occurredAt()
        );

        log.info("[{}] applied {} ({}) for replenishment_request={} → work_order={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.replenishmentRequestId(), payload.aggregateId());
    }
}
