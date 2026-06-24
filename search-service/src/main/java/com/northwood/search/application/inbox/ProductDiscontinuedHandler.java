package com.northwood.search.application.inbox;

import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.search.application.ProductCatalogProjection;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Flags a product discontinued (status facet) in the {@code products} index. */
@Component
public class ProductDiscontinuedHandler extends AbstractEventProjectionHandler<ProductDiscontinued> {

    public static final String HANDLER_NAME = "search.products.product-discontinued";

    private final ProductCatalogProjection products;

    public ProductDiscontinuedHandler(ObjectMapper json, ProductCatalogProjection products) {
        super(json, ProductDiscontinued.class, ProductDiscontinued.EVENT_TYPE, HANDLER_NAME);
        this.products = products;
    }

    @Override
    protected void apply(ProductDiscontinued payload, EventEnvelope envelope) {
        products.markDiscontinued(payload.aggregateId());
    }
}
