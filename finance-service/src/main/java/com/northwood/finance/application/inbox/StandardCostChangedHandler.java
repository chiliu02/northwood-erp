package com.northwood.finance.application.inbox;

import com.northwood.product.domain.events.StandardCostChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.8 Slice B: idempotent inbox handler for
 * {@code product.StandardCostChanged}. Maintains finance's
 * {@code product_standard_cost} projection so {@code JournalEntryService}'s
 * COGS posting path can read finance's authoritative cost rather than
 * trusting whatever the warehouse clerk typed onto the shipment line.
 */
@Component
public class StandardCostChangedHandler extends AbstractInboxHandler<StandardCostChanged> {

    public static final String CONSUMER_NAME = "finance.product-standard-cost-projector";

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
