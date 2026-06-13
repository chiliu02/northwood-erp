package com.northwood.reporting.application.inbox.atp;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.product.domain.events.ProductCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

/**
 * ATP consumer of {@code product.ProductCreated}. Fills in
 * {@code product_sku} + {@code product_name} on stub rows that an earlier
 * reservation event created (reservations don't carry product identity),
 * or creates a zero-quantity row so subsequent reservations land
 * pre-identified. The reporting service already subscribes to
 * {@code product.events}, so no bus-config change.
 */
@Component("atp_ProductCreatedHandler")
public class ProductCreatedHandler extends AbstractInboxHandler<ProductCreated> {

    public static final String HANDLER_NAME = "reporting.atp.product-created";

    private final AvailableToPromiseProjection projection;

    public ProductCreatedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ProductCreated.class, ProductCreated.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ProductCreated payload, EventEnvelope envelope) {
        projection.recordProductCreated(
            payload.aggregateId(), payload.sku(), payload.name(), payload.occurredAt());
    }
}
