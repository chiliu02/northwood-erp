package com.northwood.finance.application.inbox;

import com.northwood.product.domain.events.ValuationClassChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code product.ValuationClassChanged}. Updates
 * the {@code valuation_class} column on {@code finance.product_card}
 * so the GL-posting paths in
 * {@link com.northwood.finance.application.JournalEntryService} (via
 * {@code ProductCardLookup.findValuationClass}) can pick raw-materials
 * vs. finished-goods inventory + COGS account codes.
 */
@Component
public class ValuationClassChangedHandler extends AbstractInboxHandler<ValuationClassChanged> {

    public static final String HANDLER_NAME = "finance.product-valuation-class-projector";

    private final ProductCardProjection projection;

    public ValuationClassChangedHandler(
        InboxPort inbox,
        ProductCardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ValuationClassChanged.class, ValuationClassChanged.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ValuationClassChanged payload, EventEnvelope envelope) {
        projection.applyValuationClass(payload.aggregateId(), payload.newValuationClass());
    }
}
