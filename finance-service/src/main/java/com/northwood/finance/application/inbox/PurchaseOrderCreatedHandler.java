package com.northwood.finance.application.inbox;

import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code purchasing.PurchaseOrderCreated}.
 * Seeds one {@code finance.purchase_order_line_facts} row per PO line so
 * 3-way match has somewhere to compare against.
 */
@Component
public class PurchaseOrderCreatedHandler extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String CONSUMER_NAME = "finance.po-line-facts.po-created";

    private final PurchaseOrderLineFactsProjection projection;

    public PurchaseOrderCreatedHandler(
        InboxPort inbox,
        PurchaseOrderLineFactsProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCreated.class, PurchaseOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(PurchaseOrderCreated payload, EventEnvelope envelope) {
        for (PurchaseOrderCreated.OrderLine line : payload.lines()) {
            projection.applyPurchaseOrderCreated(
                payload.aggregateId(),
                payload.supplierId(),
                payload.supplierName(),
                payload.currencyCode(),
                line.lineId(),
                line.productId(),
                line.productSku(),
                line.productName(),
                line.orderedQuantity(),
                line.unitPrice()
            );
        }

        log.info("[{}] seeded po_line_facts for purchase_order={} ({} line(s))",
            CONSUMER_NAME, payload.aggregateId(), payload.lines().size());
    }
}
