package com.northwood.manufacturing.application.inbox;

import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.MakeVsBuyChanged}. Updates the
 * {@code manufacturing.product_card} projection. Mirrors sales' own
 * Shape A handler ({@code SalesPriceChangedHandler}) — both treat product
 * as the master and project per-facet outcomes locally.
 */
@Component
public class MakeVsBuyChangedHandler extends AbstractInboxHandler<MakeVsBuyChanged> {

    public static final String HANDLER_NAME = "manufacturing.product-replenishment-projector";

    private final ProductReplenishmentProjection projection;

    public MakeVsBuyChangedHandler(
        InboxPort inbox,
        ProductReplenishmentProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, MakeVsBuyChanged.class, MakeVsBuyChanged.EVENT_TYPE, HANDLER_NAME);
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
            HANDLER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newIsPurchased(), payload.newIsManufactured());
    }
}
