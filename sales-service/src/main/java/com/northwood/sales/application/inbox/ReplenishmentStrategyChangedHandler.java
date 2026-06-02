package com.northwood.sales.application.inbox;

import com.northwood.product.domain.events.ReplenishmentStrategyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ReplenishmentStrategyChanged}
 * (§2.43). Projects the new strategy onto the {@code sales.product_card} row so
 * the fulfilment saga reads make-to-order vs make-to-stock locally rather than
 * across schemas — the per-service search_path blocks cross-schema queries
 * anyway, and a denormalised projection is the established Shape A pattern for
 * non-product services consuming product-master facets.
 *
 * <p>Pairs with {@link SalesPriceChangedHandler} on the same topic; both treat
 * product-master events as their data source and write idempotently against an
 * inbox row keyed on {@code (event_id, consumer)}.
 */
@Component
public class ReplenishmentStrategyChangedHandler extends AbstractInboxHandler<ReplenishmentStrategyChanged> {

    public static final String CONSUMER_NAME = "sales.product-replenishment-strategy-projector";

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
        projection.applyReplenishmentStrategy(
            payload.aggregateId(),
            payload.newReplenishmentStrategy()
        );

        log.info("[{}] applied {} ({}) for product_id={} → {}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newReplenishmentStrategy());
    }
}
