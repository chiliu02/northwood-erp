package com.northwood.reporting.application.inbox.atp;

import com.northwood.product.domain.events.ProductDiscontinued;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §1F.1: ATP consumer of {@code product.ProductDiscontinued}. Stamps
 * {@code reporting.available_to_promise_view.discontinued_at} so UI
 * consumers can filter / grey out the row.
 */
@Component("atp_ProductDiscontinuedHandler")
public class ProductDiscontinuedHandler extends AbstractInboxHandler<ProductDiscontinued> {

    public static final String CONSUMER_NAME = "reporting.atp.product-discontinued";

    private final AvailableToPromiseProjection projection;

    public ProductDiscontinuedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ProductDiscontinued.class, ProductDiscontinued.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ProductDiscontinued payload, EventEnvelope envelope) {
        projection.recordProductDiscontinued(payload.aggregateId(), payload.occurredAt());
    }
}
