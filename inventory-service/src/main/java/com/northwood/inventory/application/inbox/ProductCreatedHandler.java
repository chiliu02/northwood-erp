package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductCreated}. Seeds inventory's
 * consolidated {@code inventory.product_card} row so subsequent product-master
 * events for the SKU ({@code ReorderPolicyChanged}, {@code MakeVsBuyChanged},
 * {@code ProductDiscontinued}) have a row to project onto. The seed carries the
 * descriptive columns (sku/name/type/uom) and derives the make-vs-buy defaults
 * from the product type so the detection service has non-empty flags for
 * day-zero SKUs before any {@code MakeVsBuyChanged} arrives. Closes the race
 * where a product registered after boot would have its
 * {@code ReorderPolicyChanged} arrive before a row exists and silently no-op.
 */
@Component
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String HANDLER_NAME = "inventory.product-created";

    private final ProductCardProjection productCard;

    public ProductCreatedHandler(
        InboxPort inbox,
        ProductCardProjection productCard,
        ObjectMapper json
    ) {
        super(inbox, json, ProductCreated.class, ProductCreated.EVENT_TYPE, HANDLER_NAME);
        this.productCard = productCard;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        productCard.applyCreated(
            payload.aggregateId(),
            payload.sku(),
            payload.name(),
            payload.productType()
        );

        log.info("[{}] applied {} ({}) for product_id={} sku={}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.sku());
    }
}
