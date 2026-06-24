package com.northwood.search.application.inbox;

import com.northwood.product.domain.events.SalesPriceChanged;
import com.northwood.search.application.ProductCatalogProjection;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Partial-updates a product's sales price in the {@code products} index. */
@Component
public class SalesPriceChangedHandler extends AbstractEventProjectionHandler<SalesPriceChanged> {

    public static final String HANDLER_NAME = "search.products.sales-price-changed";

    private final ProductCatalogProjection products;

    public SalesPriceChangedHandler(ObjectMapper json, ProductCatalogProjection products) {
        super(json, SalesPriceChanged.class, SalesPriceChanged.EVENT_TYPE, HANDLER_NAME);
        this.products = products;
    }

    @Override
    protected void apply(SalesPriceChanged payload, EventEnvelope envelope) {
        products.updateSalesPrice(payload.aggregateId(), payload.newSalesPrice(), payload.currencyCode());
    }
}
