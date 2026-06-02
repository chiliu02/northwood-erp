package com.northwood.inventory.application.inbox;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code purchasing.PurchaseOrderCreated}. Seeds
 * one {@code inventory.purchase_order_line_facts} row per PO line so the
 * goods-receipt write path has somewhere to validate
 * {@code (purchase_order_line_id, product_id)} against.
 *
 * <p>Also bridges the PR→PO link. When the originating PR was raised by
 * purchasing's {@code ReplenishmentRequestedHandler} (i.e.
 * {@code payload.purchaseRequisitionHeaderId()} matches a
 * {@link ReplenishmentRequest} dispatched to that PR), stamps
 * {@code linked_purchase_order_id} so the eventual
 * {@code inventory.GoodsReceived} can resolve back to the replenishment via
 * {@link ReplenishmentRequestRepository#findByLinkedPurchaseOrderId}.
 *
 * <p>Race tolerance: if the {@code purchasing.ReplenishmentDispatched} event
 * hasn't been processed yet (different Kafka partition), the lookup returns
 * empty and the link doesn't happen — the request will still be flipped to
 * {@code dispatched} when the dispatch event lands, but the PO link is lost
 * for that race. Documented as a known gap; mitigation would be a sidecar
 * projection of {@code source_purchase_requisition_id} on
 * {@code purchase_order_line_facts} so the dispatch handler can second-chance
 * the link. Deferred until observed in production.
 */
@Component
public class PurchaseOrderCreatedHandler extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String CONSUMER_NAME = "inventory.purchase-order-line-facts";

    private final PurchaseOrderLineFactsProjection projection;
    private final ReplenishmentRequestRepository replenishmentRequests;

    public PurchaseOrderCreatedHandler(
        InboxPort inbox,
        PurchaseOrderLineFactsProjection projection,
        ReplenishmentRequestRepository replenishmentRequests,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCreated.class, PurchaseOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
        this.replenishmentRequests = replenishmentRequests;
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

        // Bridge the PR→PO link for replenishment-driven PRs.
        if (payload.purchaseRequisitionHeaderId() != null) {
            Optional<ReplenishmentRequest> r =
                replenishmentRequests.findByDispatchedAggregateId(payload.purchaseRequisitionHeaderId());
            if (r.isPresent()
                && r.get().dispatchedAggregateKind() == ReplenishmentRequest.DispatchedAggregateKind.PURCHASE_REQUISITION) {
                ReplenishmentRequest req = r.get();
                req.linkPurchaseOrder(payload.aggregateId());
                replenishmentRequests.save(req);
                log.info("[{}] linked replenishment_request={} to purchase_order={} (via PR={})",
                    CONSUMER_NAME, req.id().value(), payload.aggregateId(), payload.purchaseRequisitionHeaderId());
            }
        }

        log.info("[{}] seeded purchase_order_line_facts for purchase_order={} ({} line(s))",
            CONSUMER_NAME, payload.aggregateId(), payload.lines().size());
    }
}
