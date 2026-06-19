package com.northwood.inventory.application.inbox;

import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code sales.SalesOrderPlaced}. Seeds one
 * {@code inventory.sales_order_line_facts} row per SO line so the shipment
 * write path has somewhere to validate {@code (sales_order_line_id, product_id)}
 * against.
 */
@Component
public class SalesOrderPlacedHandler extends AbstractInboxHandler<SalesOrderPlaced> {

    public static final String HANDLER_NAME = "inventory.sales-order-line-facts";

    private final SalesOrderLineFactsProjection projection;

    public SalesOrderPlacedHandler(
        InboxPort inbox,
        SalesOrderLineFactsProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderPlaced.class, SalesOrderPlaced.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderPlaced payload, EventEnvelope envelope) {
        for (SalesOrderPlaced.PlacedLine line : payload.lines()) {
            projection.applySalesOrderPlaced(
                payload.aggregateId(),
                line.lineId(),
                line.productId(),
                line.orderedQuantity(),
                payload.paymentTerms()
            );
        }

        log.info("[{}] seeded sales_order_line_facts for sales_order={} ({} line(s), payment_terms={})",
            HANDLER_NAME, payload.aggregateId(), payload.lines().size(), payload.paymentTerms());
    }
}
