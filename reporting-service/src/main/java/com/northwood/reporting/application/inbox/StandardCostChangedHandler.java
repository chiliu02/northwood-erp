package com.northwood.reporting.application.inbox;

import com.northwood.product.domain.events.StandardCostChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.1 Slice 2: idempotent inbox handler for
 * {@code product.StandardCostChanged}. Maintains reporting's
 * {@code product_standard_cost} cache so the financial-dashboard snapshot
 * can compute {@code inventory_value} without a cross-schema read into
 * {@code finance.product_standard_cost}.
 */
@Component
public class StandardCostChangedHandler extends AbstractInboxHandler<StandardCostChanged> {

    public static final String CONSUMER_NAME = "reporting.product-standard-cost-projector";

    private final ProductStandardCostProjection projection;

    public StandardCostChangedHandler(
        InboxPort inbox,
        ProductStandardCostProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, StandardCostChanged.class, StandardCostChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(StandardCostChanged payload, EventEnvelope envelope) {
        projection.apply(payload.aggregateId(), payload.newStandardCost(), payload.currencyCode());
    }
}
