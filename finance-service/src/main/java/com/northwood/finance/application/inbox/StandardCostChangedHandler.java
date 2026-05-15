package com.northwood.finance.application.inbox;

import com.northwood.product.domain.events.StandardCostChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.StandardCostChanged}. Updates
 * the {@code standard_cost} + {@code currency_code} columns on
 * {@code finance.product_accounting} so the COGS-posting path
 * ({@link ShipmentPostedCogsHandler} → {@code ProductAccountingLookup})
 * reads finance's authoritative cost rather than trusting whatever
 * {@code unitCost} the warehouse clerk typed onto the shipment line.
 */
@Component
public class StandardCostChangedHandler extends AbstractInboxHandler<StandardCostChanged> {

    public static final String CONSUMER_NAME = "finance.product-standard-cost-projector";

    private final ProductAccountingProjection projection;

    public StandardCostChangedHandler(
        InboxPort inbox,
        ProductAccountingProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, StandardCostChanged.class, StandardCostChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(StandardCostChanged payload, EventEnvelope envelope) {
        projection.applyStandardCost(payload.aggregateId(), payload.newStandardCost(), payload.currencyCode());
    }
}
