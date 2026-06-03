package com.northwood.purchasing.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductCreated}. Upserts the
 * product's sku + name onto {@code purchasing.product_card} so the
 * supplier-price list view renders a SKU/name rather than a raw product UUID.
 */
@Component
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String CONSUMER_NAME = "purchasing.product-created";

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
        projection.applyCreated(payload.aggregateId(), payload.sku(), payload.name());

        log.info("[{}] applied {} ({}) for product_id={} (sku={})",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.sku());
    }
}
