package com.northwood.inventory.application.inbox;

import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequest.DispatchedAggregateKind;
import com.northwood.inventory.domain.ReplenishmentRequestId;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.manufacturing.domain.events.ReplenishmentDispatched;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code manufacturing.ReplenishmentDispatched}.
 * Looks up the originating {@link ReplenishmentRequest} by
 * {@code replenishmentRequestId} and flips it to {@code dispatched}, recording
 * the work-order id so a later {@code WorkOrderManufacturingCompleted} can
 * resolve back to it.
 *
 * <p>If the request can't be found (orphan dispatch event for a
 * never-recorded request — e.g. someone hand-emitted the event for testing,
 * or a saga-state-inconsistency edge case), the handler logs a WARN and
 * records the inbox row as processed without throwing — better to make the
 * inbox row durable than to keep redelivering an event nothing can ever
 * process.
 */
@Component
public class ManufacturingReplenishmentDispatchedHandler
    extends AbstractInboxHandler<ReplenishmentDispatched> {

    public static final String CONSUMER_NAME = "inventory.replenishment.mfg-dispatched";

    private final ReplenishmentRequestRepository replenishmentRequests;

    public ManufacturingReplenishmentDispatchedHandler(
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
            log.warn("[{}] {} ({}) for unknown replenishment_request={} (work_order={}) — ignored",
                CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
                payload.replenishmentRequestId(), payload.aggregateId());
            return;
        }
        ReplenishmentRequest r = existing.get();
        r.markDispatched(DispatchedAggregateKind.WORK_ORDER, payload.aggregateId());
        replenishmentRequests.save(r);

        log.info("[{}] replenishment={} dispatched to work_order={}",
            CONSUMER_NAME, r.id().value(), payload.aggregateId());
    }
}
