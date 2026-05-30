package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ProductDiscontinued}. Stamps
 * {@code inventory.product_card.discontinued_at} so reorder-alert logic can
 * suppress alerts for retired SKUs, and flips {@code is_purchased = false,
 * is_manufactured = false} so the §2.35 detection service classifies the SKU as
 * unsourceable (logs + skips) rather than dispatching a replenishment.
 */
@Component
public class ProductDiscontinuedHandler extends AbstractInboxHandler<ProductDiscontinued> {

    public static final String CONSUMER_NAME = "inventory.product-discontinued";

    private final ProductCardProjection productCard;

    public ProductDiscontinuedHandler(
        InboxPort inbox,
        ProductCardProjection productCard,
        ObjectMapper json
    ) {
        super(inbox, json, ProductDiscontinued.class, ProductDiscontinued.EVENT_TYPE, CONSUMER_NAME);
        this.productCard = productCard;
    }

    @Override
    protected void apply(ProductDiscontinued payload, EventEnvelope envelope) {
        productCard.applyDiscontinued(payload.aggregateId(), payload.occurredAt());

        log.info("[{}] applied {} ({}) for product_id={} (at={})",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.occurredAt());
    }
}
