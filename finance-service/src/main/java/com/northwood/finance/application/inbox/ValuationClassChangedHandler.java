package com.northwood.finance.application.inbox;

import com.northwood.product.domain.events.ValuationClassChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ValuationClassChangedHandler extends AbstractInboxHandler<ValuationClassChanged> {

    public static final String CONSUMER_NAME = "finance.product-valuation-class-projector";

    private final ProductValuationClassProjection projection;

    public ValuationClassChangedHandler(
        InboxPort inbox,
        ProductValuationClassProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ValuationClassChanged.class, ValuationClassChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ValuationClassChanged payload, EventEnvelope envelope) {
        projection.apply(payload.aggregateId(), payload.newValuationClass());
    }
}
