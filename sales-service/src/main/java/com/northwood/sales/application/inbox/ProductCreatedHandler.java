package com.northwood.sales.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductCreated}. Seeds a stub
 * {@code sales.product_card} row so {@link SalesPriceChangedHandler} and
 * {@link ProductDiscontinuedHandler} always find a row to update. Pairs with
 * {@code inventory.product-created} and {@code manufacturing.product-replenishment-seeder};
 * the three together make every product-fact projection's lifetime mirror the
 * Product aggregate's.
 */
@Component
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String CONSUMER_NAME = "sales.product-created";

    private final ProductCreatedProjection projection;

    public ProductCreatedHandler(
        InboxPort inbox,
        ProductCreatedProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ProductCreated.class, ProductCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        projection.apply(payload.aggregateId());

        log.info("[{}] applied {} ({}) for product_id={} sku={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.sku());
    }
}
