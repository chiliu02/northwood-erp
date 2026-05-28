package com.northwood.inventory.application.inbox;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.purchasing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §2.35 Slice E: idempotent inbox handler for
 * {@code purchasing.ReplenishmentDispatched}. Sibling of
 * {@link ManufacturingReplenishmentDispatchedHandler} — flips the originating
 * {@link ReplenishmentRequest} to {@code dispatched} and records the
 * purchase-requisition id so the PR→PO bridge ({@code PurchaseOrderCreatedHandler})
 * can later link the resulting PO.
 *
 * <p>Note: the two
 * {@code ReplenishmentDispatched} events sit in different Java packages
 * ({@code com.northwood.manufacturing.domain.events} vs
 * {@code com.northwood.purchasing.domain.events}) and carry distinct
 * {@code EVENT_TYPE} strings, so the registration / dispatcher routing is
 * unambiguous despite the shared class name.
 */
@Component
public class PurchasingReplenishmentDispatchedHandler
    extends AbstractInboxHandler<ReplenishmentDispatched> {

    public static final String CONSUMER_NAME = "inventory.replenishment.pur-dispatched";

    private final ReplenishmentRequestRepository replenishmentRequests;

    public PurchasingReplenishmentDispatchedHandler(
        InboxPort inbox,
        ReplenishmentRequestRepository replenishmentRequests,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentDispatched.class, ReplenishmentDispatched.EVENT_TYPE, CONSUMER_NAME);
        this.replenishmentRequests = replenishmentRequests;
    }

    @Override
    protected void apply(ReplenishmentDispatched payload, EventEnvelope envelope) {
        Optional<ReplenishmentRequest> existing = replenishmentRequests.findById(
            ReplenishmentRequestId.of(payload.replenishmentRequestId())
        );
        if (existing.isEmpty()) {
            log.warn("[{}] {} ({}) for unknown replenishment_request={} (purchase_requisition={}) — ignored",
                CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
                payload.replenishmentRequestId(), payload.aggregateId());
            return;
        }
        ReplenishmentRequest r = existing.get();
        r.markDispatched(DispatchedAggregateKind.PURCHASE_REQUISITION, payload.aggregateId());
        replenishmentRequests.save(r);

        log.info("[{}] replenishment={} dispatched to purchase_requisition={}",
            CONSUMER_NAME, r.id().value(), payload.aggregateId());
    }
}
