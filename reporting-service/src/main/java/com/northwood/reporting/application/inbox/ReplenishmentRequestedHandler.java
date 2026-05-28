package com.northwood.reporting.application.inbox;

import com.northwood.inventory.domain.events.ReplenishmentRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice F: reporting consumer of {@code inventory.ReplenishmentRequested}.
 * Lands a new row in {@code reporting.replenishment_history_view} in
 * {@code 'requested'} status.
 */
@Component
public class ReplenishmentRequestedHandler extends AbstractInboxHandler<ReplenishmentRequested> {

    public static final String CONSUMER_NAME = "reporting.replenishment-history.requested";

    private final ReplenishmentHistoryProjection projection;

    public ReplenishmentRequestedHandler(
        InboxPort inbox,
        ReplenishmentHistoryProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentRequested.class, ReplenishmentRequested.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ReplenishmentRequested payload, EventEnvelope envelope) {
        projection.recordRequested(
            payload.aggregateId(),
            payload.productId(),
            payload.warehouseId(),
            payload.quantity(),
            payload.targetService(),
            payload.reason(),
            payload.occurredAt()
        );

        log.info("[{}] applied {} ({}) for replenishment_request={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
