package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.MakeVsBuyChanged}. Updates the
 * {@code inventory.product_card} projection so the reorder-point detection
 * service reads make-vs-buy locally rather than across schemas. Mirrors
 * manufacturing's own
 * {@link com.northwood.manufacturing.application.inbox.MakeVsBuyChangedHandler}
 * — both treat product as the master and project per-facet outcomes locally.
 */
@Component
public class MakeVsBuyChangedHandler extends AbstractInboxHandler<MakeVsBuyChanged> {

    public static final String CONSUMER_NAME = "inventory.product-replenishment-projector";

    private final ProductCardProjection projection;

    public MakeVsBuyChangedHandler(
        InboxPort inbox,
        ProductCardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, MakeVsBuyChanged.class, MakeVsBuyChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(MakeVsBuyChanged payload, EventEnvelope envelope) {
        projection.applyMakeVsBuy(
            payload.aggregateId(),
            payload.newIsPurchased(),
            payload.newIsManufactured()
        );

        log.info("[{}] applied {} ({}) for product_id={} → purchased={}, manufactured={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newIsPurchased(), payload.newIsManufactured());
    }
}
