package com.northwood.sales.application.inbox;

import com.northwood.product.domain.events.PlanningTimeFenceChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.PlanningTimeFenceChanged}.
 * Projects the new fence onto the {@code sales.product_card} row so the
 * fulfilment saga reads it locally at placement rather than across schemas —
 * the per-service search_path blocks cross-schema queries anyway, and a
 * denormalised projection is the established Shape A pattern for non-product
 * services consuming product-master facets.
 *
 * <p>Pairs with {@link SalesPriceChangedHandler} / {@link ReplenishmentStrategyChangedHandler}
 * on the same topic; all treat product-master events as their data source and
 * write idempotently against an inbox row keyed on {@code (event_id, consumer)}.
 */
@Component
public class PlanningTimeFenceChangedHandler extends AbstractInboxHandler<PlanningTimeFenceChanged> {

    public static final String CONSUMER_NAME = "sales.product-planning-time-fence-projector";

    private final PlanningTimeFenceProjection projection;

    public PlanningTimeFenceChangedHandler(
        InboxPort inbox,
        PlanningTimeFenceProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, PlanningTimeFenceChanged.class, PlanningTimeFenceChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(PlanningTimeFenceChanged payload, EventEnvelope envelope) {
        projection.applyPlanningTimeFence(
            payload.aggregateId(),
            payload.newPlanningTimeFenceDays()
        );

        log.info("[{}] applied {} ({}) for product_id={} → {} day(s)",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newPlanningTimeFenceDays());
    }
}
