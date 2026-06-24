package com.northwood.search.application.inbox.entity;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.search.application.GlobalEntityProjection;
import com.northwood.search.application.inbox.AbstractEventProjectionHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Indexes a product into the global {@code entities} index (read model B). */
@Component
public class EntityProductCreatedHandler extends AbstractEventProjectionHandler<ProductCreated> {

    public static final String HANDLER_NAME = "search.entities.product-created";

    private final GlobalEntityProjection entities;

    public EntityProductCreatedHandler(ObjectMapper json, GlobalEntityProjection entities) {
        super(json, ProductCreated.class, ProductCreated.EVENT_TYPE, HANDLER_NAME);
        this.entities = entities;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        entities.upsertEntity(
            GlobalEntityProjection.TYPE_PRODUCT, payload.aggregateId(),
            payload.name(), payload.sku(), payload.productType(), "active");
    }
}
