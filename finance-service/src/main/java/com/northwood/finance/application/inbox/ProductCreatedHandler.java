package com.northwood.finance.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductCreated}. Seeds a stub
 * {@code finance.product_accounting} row so subsequent
 * {@link StandardCostChangedHandler}, {@link ValuationClassChangedHandler},
 * and {@link ProductDiscontinuedHandler} always find a row to update. Pairs
 * with {@code sales.product-created}, {@code inventory.product-created}, and
 * {@code manufacturing.product-replenishment-seeder} — the four together
 * make every product-fact projection's lifetime mirror the Product
 * aggregate's.
 */
@Component
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String CONSUMER_NAME = "finance.product-created";

    private final ProductAccountingProjection projection;

    public ProductCreatedHandler(
        InboxPort inbox,
        ProductAccountingProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ProductCreated.class, ProductCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        projection.seed(payload.aggregateId());

        log.info("[{}] applied {} ({}) for product_id={} sku={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.sku());
    }
}
