package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice A: idempotent inbox handler for {@code product.MakeVsBuyChanged}.
 * Updates the {@code inventory.product_replenishment} projection so the §2.35
 * reorder-point detection service (Slice B) reads make-vs-buy locally rather
 * than across schemas. Mirrors manufacturing's own
 * {@link com.northwood.manufacturing.application.inbox.MakeVsBuyChangedHandler}
 * — both treat product as the master and project per-facet outcomes locally.
 */
@Component
public class MakeVsBuyChangedHandler extends AbstractInboxHandler<MakeVsBuyChanged> {

    public static final String CONSUMER_NAME = "inventory.product-replenishment-projector";

    private final ProductReplenishmentProjection projection;

    public MakeVsBuyChangedHandler(
        InboxPort inbox,
        ProductReplenishmentProjection projection,
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
