package com.northwood.inventory.application.inbox;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind;
import com.northwood.inventory.domain.ReplenishmentRequest.Status;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code purchasing.PurchaseOrderCreated} owning the
 * replenishment PR→PO link. Sibling of
 * {@link PurchasingReplenishmentDispatchedHandler} — together they are the
 * inventory replenishment concern's view of the purchasing leg (kept here rather
 * than bolted onto the line-facts seeder — a cohesion fix; the race itself came
 * from the two purchasing events being keyed by different aggregate ids, and is
 * closed by the order-independence below, not by the move).
 *
 * <p><strong>Self-sufficient / order-independent.</strong> A
 * {@code PurchaseOrderCreated} carrying {@code sourceReplenishmentRequestId} is
 * proof its originating {@link ReplenishmentRequest} was dispatched — the PR
 * (and therefore the {@code ReplenishmentDispatched} event) exist only because
 * the request was dispatched to purchasing. So this handler does
 * {@link ReplenishmentRequest#markDispatched} <em>and</em>
 * {@link ReplenishmentRequest#linkPurchaseOrder} in one transaction rather than
 * waiting for {@code ReplenishmentDispatched} to have been processed first. Both
 * calls are idempotent, so whichever of the two events arrives first wins and
 * the other no-ops — closing the former dispatch-vs-PO-created race with no
 * partition/ordering assumption. {@code ReplenishmentDispatched} remains the
 * dispatch record for the window where a PR exists but its PO has not yet been
 * created.
 *
 * <p>Manual (non-replenishment) POs carry no
 * {@code sourceReplenishmentRequestId} and are ignored.
 */
@Component
public class ReplenishmentPurchaseOrderLinkHandler
    extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String CONSUMER_NAME = "inventory.replenishment.pur-po-created";

    private final ReplenishmentRequestRepository replenishmentRequests;

    public ReplenishmentPurchaseOrderLinkHandler(
        InboxPort inbox,
        ReplenishmentRequestRepository replenishmentRequests,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCreated.class, PurchaseOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.replenishmentRequests = replenishmentRequests;
    }

    @Override
    protected void apply(PurchaseOrderCreated payload, EventEnvelope envelope) {
        if (payload.sourceReplenishmentRequestId() == null) {
            return;  // not a replenishment-driven PO — nothing to link
        }
        Optional<ReplenishmentRequest> found = replenishmentRequests.findById(
            ReplenishmentRequestId.of(payload.sourceReplenishmentRequestId()));
        if (found.isEmpty()) {
            log.warn("[{}] {} ({}) for unknown replenishment_request={} (purchase_order={}) — ignored",
                CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
                payload.sourceReplenishmentRequestId(), payload.aggregateId());
            return;
        }
        ReplenishmentRequest r = found.get();
        if (r.status() != Status.REQUESTED && r.status() != Status.DISPATCHED) {
            return;  // terminal (fulfilled / cancelled) — any link was stamped earlier; redelivery no-op
        }
        // markDispatched: requested -> dispatched, or idempotent no-op if ReplenishmentDispatched already won the race.
        r.markDispatched(DispatchedAggregateKind.PURCHASE_REQUISITION, payload.purchaseRequisitionHeaderId());
        r.linkPurchaseOrder(payload.aggregateId());   // idempotent: same PO no-ops, a different PO throws
        replenishmentRequests.save(r);

        log.info("[{}] linked replenishment_request={} to purchase_order={} (via PurchaseOrderCreated, PR={})",
            CONSUMER_NAME, r.id().value(), payload.aggregateId(), payload.purchaseRequisitionHeaderId());
    }
}
