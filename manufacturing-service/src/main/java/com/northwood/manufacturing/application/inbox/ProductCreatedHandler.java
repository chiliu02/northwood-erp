package com.northwood.manufacturing.application.inbox;

import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §3.1: seed a default {@code manufacturing.product_replenishment} row at
 * product registration so newly-registered products are sourceable from the
 * moment they're created — rather than the previous "no row → reject" default.
 *
 * <p>Default flags derived from {@code product_type} (see
 * {@link ProductReplenishmentProjection#seedDefaultsFromProductType}).
 * A later {@code product.MakeVsBuyChanged} from the same product overrides
 * the seed.
 */
@Component
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String CONSUMER_NAME = "manufacturing.product-replenishment-seeder";

    private final ProductReplenishmentProjection projection;

    public ProductCreatedHandler(
        InboxPort inbox,
        ProductReplenishmentProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ProductCreated.class, ProductCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        projection.seedDefaultsFromProductType(payload.aggregateId(), payload.productType());

        log.info("[{}] seeded {} ({}) for product_id={}, type={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.productType());
    }
}
