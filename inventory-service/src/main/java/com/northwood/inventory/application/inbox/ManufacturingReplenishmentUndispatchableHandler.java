package com.northwood.inventory.application.inbox;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.manufacturing.domain.events.ReplenishmentUndispatchable;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code manufacturing.ReplenishmentUndispatchable}
 * — manufacturing couldn't release a work order for the request (no active BOM).
 * Looks up the originating {@link ReplenishmentRequest} and cancels it
 * ({@code markCancelled}), which emits {@code inventory.ReplenishmentCancelled};
 * for a {@code sales_order_shortage} request that flips the sales order to
 * {@code rejected} via sales' fan-in.
 *
 * <p>Failure counterpart of {@link ManufacturingReplenishmentDispatchedHandler}.
 * If the request can't be found (orphan event) the handler logs a WARN and
 * records the inbox row as processed without throwing.
 */
@Component
public class ManufacturingReplenishmentUndispatchableHandler
    extends AbstractInboxHandler<ReplenishmentUndispatchable> {

    public static final String CONSUMER_NAME = "inventory.replenishment.mfg-undispatchable";

    private final ReplenishmentRequestRepository replenishmentRequests;

    public ManufacturingReplenishmentUndispatchableHandler(
        InboxPort inbox,
        ReplenishmentRequestRepository replenishmentRequests,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentUndispatchable.class, ReplenishmentUndispatchable.EVENT_TYPE, CONSUMER_NAME);
        this.replenishmentRequests = replenishmentRequests;
    }

    @Override
    protected void apply(ReplenishmentUndispatchable payload, EventEnvelope envelope) {
        Optional<ReplenishmentRequest> existing = replenishmentRequests.findById(
            ReplenishmentRequestId.of(payload.replenishmentRequestId())
        );
        if (existing.isEmpty()) {
            log.warn("[{}] {} ({}) for unknown replenishment_request={} (product={}) — ignored",
                CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
                payload.replenishmentRequestId(), payload.productId());
            return;
        }
        ReplenishmentRequest r = existing.get();
        r.markCancelled(payload.reason());
        replenishmentRequests.save(r);

        log.info("[{}] replenishment={} cancelled (manufacturing undispatchable: {})",
            CONSUMER_NAME, r.id().value(), payload.reason());
    }
}
