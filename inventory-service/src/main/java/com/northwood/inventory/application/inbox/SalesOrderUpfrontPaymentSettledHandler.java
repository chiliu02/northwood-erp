package com.northwood.inventory.application.inbox;

import com.northwood.sales.domain.events.SalesOrderUpfrontPaymentSettled;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.SalesOrderUpfrontPaymentSettled}.
 * Flips {@code upfront_settled = true} on every line of the order so
 * {@code ShipmentService.post} accepts the next shipment for it (the up-front
 * payment — prepayment or deposit — has landed). Inbox dedupe + the UPDATE's
 * {@code WHERE upfront_settled = false} guard make redelivery a clean no-op.
 */
@Component
public class SalesOrderUpfrontPaymentSettledHandler extends AbstractInboxHandler<SalesOrderUpfrontPaymentSettled> {

    public static final String CONSUMER_NAME = "inventory.sales-order-line-facts.upfront-settled";

    private final SalesOrderLineFactsProjection projection;

    public SalesOrderUpfrontPaymentSettledHandler(
        InboxPort inbox,
        SalesOrderLineFactsProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderUpfrontPaymentSettled.class, SalesOrderUpfrontPaymentSettled.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderUpfrontPaymentSettled payload, EventEnvelope envelope) {
        projection.applyUpfrontPaymentSettled(payload.aggregateId());

        log.info("[{}] flipped upfront_settled=true for sales_order={}",
            CONSUMER_NAME, payload.aggregateId());
    }
}
