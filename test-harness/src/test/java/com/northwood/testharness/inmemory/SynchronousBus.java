package com.northwood.testharness.inmemory;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
import com.northwood.shared.application.outbox.OutboxRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Synchronous bus connecting one or more service test-kits. Wraps each kit's
 * {@link InMemoryOutboxPort} and dispatches its pending rows to every
 * registered {@link InboxEnvelopeHandler} whose {@code handles()} matches.
 *
 * <p>{@link #drain()} runs until no outbox has any pending row left — events
 * emitted by handlers (cross-service cascades) are picked up in the same call.
 *
 * <p>Same serde as production: each handler deserialises the envelope payload
 * via the same Jackson 3 {@code ObjectMapper} the kit hands it. Wire-shape
 * regression coverage falls out for free.
 */
public final class SynchronousBus {

    private final List<InMemoryOutboxPort> outboxes = new ArrayList<>();
    private final List<InboxEnvelopeHandler> handlers = new ArrayList<>();

    /** Register a service's outbox so the bus drains it. */
    public void register(InMemoryOutboxPort outbox) {
        this.outboxes.add(outbox);
    }

    /** Register a handler. Handler decides via {@code handles(eventType)} which envelopes apply. */
    public void register(InboxEnvelopeHandler handler) {
        this.handlers.add(handler);
    }

    /**
     * Drain every pending row across every registered outbox, dispatching to
     * matching handlers. Repeats until no outbox has pending rows — events
     * emitted by handlers in this call are picked up in the same call.
     */
    public void drain() {
        boolean keepGoing = true;
        int safetyMax = 1000;
        while (keepGoing && safetyMax-- > 0) {
            keepGoing = false;
            for (InMemoryOutboxPort outbox : outboxes) {
                List<OutboxRow> pending = outbox.findPending(Integer.MAX_VALUE);
                if (pending.isEmpty()) continue;
                keepGoing = true;
                for (OutboxRow row : pending) {
                    dispatch(row);
                    row.markPublished();
                    outbox.update(row);
                }
            }
        }
        if (safetyMax <= 0) {
            throw new IllegalStateException("SynchronousBus.drain() exceeded 1000 iterations — infinite cascade?");
        }
    }

    private void dispatch(OutboxRow row) {
        EventEnvelope envelope = new EventEnvelope(
            row.getOutboxMessageId(),
            row.getAggregateType(),
            row.getAggregateId(),
            row.getEventType(),
            row.getEventVersion(),
            row.getPayload(),
            Map.of(),
            row.getCorrelationId(),
            row.getCausationId(),
            row.getActorUserId(),
            row.getCreatedAt()
        );
        for (InboxEnvelopeHandler handler : handlers) {
            if (handler.handles(envelope.eventType())) {
                handler.handle(envelope);
            }
        }
    }
}
