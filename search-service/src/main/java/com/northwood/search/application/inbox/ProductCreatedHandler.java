package com.northwood.search.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.search.application.ProductCatalogProjection;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Indexes a new product into the {@code products} search index. */
@Component
public class ProductCreatedHandler extends AbstractEventProjectionHandler<ProductCreated> {

    public static final String HANDLER_NAME = "search.products.product-created";

    private final ProductCatalogProjection products;

    public ProductCreatedHandler(ObjectMapper json, ProductCatalogProjection products) {
        super(json, ProductCreated.class, ProductCreated.EVENT_TYPE, HANDLER_NAME);
        this.products = products;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        products.upsertProduct(
            payload.aggregateId(), payload.sku(), payload.name(), payload.productType(), payload.occurredAt());
    }
}
