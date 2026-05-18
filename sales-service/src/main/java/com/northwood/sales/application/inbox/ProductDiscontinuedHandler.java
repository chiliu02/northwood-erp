package com.northwood.sales.application.inbox;

import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductDiscontinued}. Stamps
 * {@code sales.product_card.discontinued_at} so {@code placeOrder} can
 * reject lines for discontinued products without crossing schemas.
 */
@Component
public class ProductDiscontinuedHandler extends AbstractInboxHandler<ProductDiscontinued> {

    public static final String CONSUMER_NAME = "sales.product-discontinued";

    private final ProductDiscontinuedProjection projection;

    public ProductDiscontinuedHandler(
        InboxPort inbox,
        ProductDiscontinuedProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ProductDiscontinued.class, ProductDiscontinued.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ProductDiscontinued payload, EventEnvelope envelope) {
        projection.applyDiscontinued(payload.aggregateId(), payload.occurredAt());

        log.info("[{}] applied {} ({}) for product_id={} (discontinued_at={})",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.occurredAt());
    }
}
