package com.northwood.purchasing.application.inbox;

import com.northwood.product.domain.events.MakeVsBuyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.MakeVsBuyChanged}. Projects the
 * {@code is_purchased} flag onto {@code purchasing.product_card} so
 * {@link com.northwood.purchasing.application.PurchasableProductLookup} rejects
 * a requisition line for a make-only SKU. Mirrors inventory's and
 * manufacturing's own handlers — all treat product as the master and project the
 * facet they care about locally (purchasing keeps only the purchased flag).
 */
@Component
public class MakeVsBuyChangedHandler extends AbstractInboxHandler<MakeVsBuyChanged> {

    public static final String CONSUMER_NAME = "purchasing.product-make-vs-buy";

    private final MakeVsBuyChangedProjection projection;

    public MakeVsBuyChangedHandler(
        InboxPort inbox,
        MakeVsBuyChangedProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, MakeVsBuyChanged.class, MakeVsBuyChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(MakeVsBuyChanged payload, EventEnvelope envelope) {
        projection.applyMakeVsBuy(payload.aggregateId(), payload.newIsPurchased());

        log.info("[{}] applied {} ({}) for product_id={} → purchased={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newIsPurchased());
    }
}
