package com.northwood.reporting.application.inbox;

import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice F: reporting consumer of
 * {@code inventory.ReplenishmentFulfilled}. Flips the
 * {@code reporting.replenishment_history_view} row to {@code 'fulfilled'}
 * and stamps {@code fulfilled_at}. Terminal state on the projection.
 */
@Component
public class ReplenishmentFulfilledHandler extends AbstractInboxHandler<ReplenishmentFulfilled> {

    public static final String CONSUMER_NAME = "reporting.replenishment-history.fulfilled";

    private final ReplenishmentHistoryProjection projection;

    public ReplenishmentFulfilledHandler(
        InboxPort inbox,
        ReplenishmentHistoryProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentFulfilled.class, ReplenishmentFulfilled.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ReplenishmentFulfilled payload, EventEnvelope envelope) {
        projection.recordFulfilled(payload.aggregateId(), payload.occurredAt());

        log.info("[{}] applied {} ({}) for replenishment_request={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
