package com.northwood.inventory.application.inbox;

import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code purchasing.PurchaseOrderCreated}. Seeds
 * one {@code inventory.purchase_order_line_facts} row per PO line so the
 * goods-receipt write path has somewhere to validate
 * {@code (purchase_order_line_id, product_id)} against.
 *
 * <p><strong>Single responsibility.</strong> The PR→PO replenishment link is
 * <em>not</em> handled here — it is owned by the replenishment concern
 * ({@link ReplenishmentPurchaseOrderLinkHandler}, a separate handler of the
 * same event). It was previously bolted on here only because this handler
 * already consumed {@code PurchaseOrderCreated}; that was a cohesion smell, not
 * the cause of the dispatch-vs-PO-created race (the race is the two purchasing
 * events being keyed by different aggregate ids → different partitions). The
 * link now lives with the dispatch concern, where it is made order-independent.
 */
@Component
public class PurchaseOrderCreatedHandler extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String CONSUMER_NAME = "inventory.purchase-order-line-facts";

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
                line.lineId(),
                line.productId()
            );
        }
        log.info("[{}] seeded purchase_order_line_facts for purchase_order={} ({} line(s))",
            CONSUMER_NAME, payload.aggregateId(), payload.lines().size());
    }
}
