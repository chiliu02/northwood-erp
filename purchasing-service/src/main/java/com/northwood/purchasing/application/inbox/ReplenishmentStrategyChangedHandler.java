package com.northwood.purchasing.application.inbox;

import com.northwood.product.domain.events.ReplenishmentStrategyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ReplenishmentStrategyChanged}.
 * Projects the {@code replenishment_strategy} flag onto
 * {@code purchasing.product_card} so
 * {@link com.northwood.purchasing.application.ToOrderProductLookup} rejects a
 * manual requisition line for a to-order SKU. Mirrors sales' handler — both treat
 * product as the master and project the strategy facet locally.
 */
@Component
public class ReplenishmentStrategyChangedHandler extends AbstractInboxHandler<ReplenishmentStrategyChanged> {

    public static final String CONSUMER_NAME = "purchasing.product-replenishment-strategy";

    private final ReplenishmentStrategyProjection projection;

    public ReplenishmentStrategyChangedHandler(
        InboxPort inbox,
        ReplenishmentStrategyProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentStrategyChanged.class, ReplenishmentStrategyChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ReplenishmentStrategyChanged payload, EventEnvelope envelope) {
        projection.applyReplenishmentStrategy(payload.aggregateId(), payload.newReplenishmentStrategy());

        log.info("[{}] applied {} ({}) for product_id={} → strategy={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newReplenishmentStrategy());
    }
}
