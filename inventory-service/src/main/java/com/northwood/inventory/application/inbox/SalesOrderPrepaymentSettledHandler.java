package com.northwood.inventory.application.inbox;

import com.northwood.sales.domain.events.SalesOrderPrepaymentSettled;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.31 Slice C. Idempotent inbox handler for
 * {@code sales.SalesOrderPrepaymentSettled}. Flips
 * {@code prepayment_settled = true} on every line of the order so
 * {@code ShipmentService.post} accepts the next shipment for it. Inbox
 * dedupe + the UPDATE's {@code WHERE prepayment_settled = false} guard make
 * redelivery a clean no-op.
 */
@Component
public class SalesOrderPrepaymentSettledHandler extends AbstractInboxHandler<SalesOrderPrepaymentSettled> {

    public static final String CONSUMER_NAME = "inventory.sales-order-line-facts.prepayment-settled";

    private final SalesOrderLineFactsProjection projection;

    public SalesOrderPrepaymentSettledHandler(
        InboxPort inbox,
        SalesOrderLineFactsProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderPrepaymentSettled.class, SalesOrderPrepaymentSettled.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderPrepaymentSettled payload, EventEnvelope envelope) {
        projection.applyPrepaymentSettled(payload.aggregateId());

        log.info("[{}] flipped prepayment_settled=true for sales_order={}",
            CONSUMER_NAME, payload.aggregateId());
    }
}
