package com.northwood.finance.application.inbox;

import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductDiscontinued}. Stamps
 * {@code finance.product_card.discontinued_at} so future audits can
 * tell that a product was retired (and so a future close-the-loop check on
 * GL postings can flag activity for discontinued SKUs).
 */
@Component
public class ProductDiscontinuedHandler extends AbstractInboxHandler<ProductDiscontinued> {

    public static final String CONSUMER_NAME = "finance.product-discontinued";

    private final ProductCardProjection projection;

    public ProductDiscontinuedHandler(
        InboxPort inbox,
        ProductCardProjection projection,
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
