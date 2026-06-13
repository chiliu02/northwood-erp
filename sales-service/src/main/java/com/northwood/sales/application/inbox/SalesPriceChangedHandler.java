package com.northwood.sales.application.inbox;

import com.northwood.product.domain.events.SalesPriceChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.SalesPriceChanged}. Updates
 * the {@code sales.product_card} projection so order-validation reads stay
 * inside the sales schema — the per-service search_path blocks cross-schema
 * queries anyway, and a denormalised projection is the established Shape A
 * pattern for non-product services consuming product-master facets.
 *
 * <p>Pairs with inventory's {@code ReorderPolicyChangedHandler} on the same
 * topic. Both treat product-master events as their data source; both write
 * idempotently against an inbox row keyed on {@code (event_id, consumer)}.
 */
@Component
public class SalesPriceChangedHandler extends AbstractInboxHandler<SalesPriceChanged> {

    public static final String HANDLER_NAME = "sales.product-card-projector";

    private final SalesPriceProjection projection;

    public SalesPriceChangedHandler(
        InboxPort inbox,
        SalesPriceProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesPriceChanged.class, SalesPriceChanged.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesPriceChanged payload, EventEnvelope envelope) {
        projection.applySalesPrice(
            payload.aggregateId(),
            payload.newSalesPrice(),
            payload.currencyCode()
        );

        log.info("[{}] applied {} ({}) for product_id={} → {} {}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newSalesPrice(), payload.currencyCode());
    }
}
