package com.northwood.inventory.application.inbox;

import com.northwood.product.domain.events.ReorderPolicyChanged;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for product-master {@code ReorderPolicyChanged}.
 *
 * <p>Calling {@link #handle(EventEnvelope)} is the single entry point used by
 * whichever bus delivery wiring is active — the in-process bus today, the
 * Kafka {@code @KafkaListener} adapter once the kafka profile is wired in
 * step 6 of the dev-todo. The handler is bus-agnostic by design.
 *
 * <p>Idempotency: the inbox row is INSERTed inside the same transaction as the
 * projection update, keyed on {@code (message_id, consumer_name)}. A redelivery
 * shortcuts on {@link InboxPort#alreadyProcessed} and is a safe no-op.
 */
@Component
public class ReorderPolicyChangedHandler extends AbstractInboxHandler<ReorderPolicyChanged> {

    public static final String CONSUMER_NAME = "inventory.stock-item-projector";

    private final ProductCardProjection projection;

    public ReorderPolicyChangedHandler(
        InboxPort inbox,
        ProductCardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, ReorderPolicyChanged.class, ReorderPolicyChanged.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(ReorderPolicyChanged payload, EventEnvelope envelope) {
        projection.applyReorderPolicy(
            payload.aggregateId(),
            payload.newReorderPoint(),
            payload.newReorderQuantity()
        );

        log.info(
            "[{}] applied {} ({}) for product_id={} → reorder_point={}, reorder_quantity={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), payload.newReorderPoint(), payload.newReorderQuantity()
        );
    }
}
