package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductCreated}. Inserts a
 * stub {@code inventory.stock_item} row so subsequent product-master events
 * for the SKU (notably {@code ReorderPolicyChanged}) have a row to project
 * onto. §1F.2: closes the gap previously documented inline on
 * {@link StockItemProjection#applyReorderPolicy}.
 */
@Component
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String CONSUMER_NAME = "inventory.product-created";

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
        projection.apply(
            payload.aggregateId(),
            payload.sku(),
            payload.name(),
            payload.productType()
        );

        log.info("[{}] applied {} ({}) for product_id={} sku={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.sku());
    }
}
