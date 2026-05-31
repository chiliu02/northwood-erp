package com.northwood.shared.application.outbox;

import com.northwood.shared.application.messaging.OutboxTraceHeaders;
import com.northwood.shared.application.security.CurrentUserAccessor;
import com.northwood.shared.domain.DomainEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The single seam for appending a domain event to a service's outbox in the
 * same transaction as the caller — the append-side companion to the outbox
 * publisher (which drains). Wraps the serialise + {@link OutboxRow#pending} +
 * {@link OutboxPort#appendPending} + actor-stamping boilerplate so every call
 * site (saga workers, inbox handlers, services, the compensation emitter) emits
 * with one line and never touches {@link OutboxPort} or the {@link ObjectMapper}
 * directly. {@link OutboxPort} is held here and nowhere else on the append path.
 *
 * <p>This is the no-aggregate-to-mutate emission path (CLAUDE.md rule #4).
 * Aggregate-driven emission still drains the aggregate's {@code pendingEvents}
 * through the per-service repository's {@code save} and does not come here.
 */
public class OutboxAppender {

    private final OutboxPort outbox;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public OutboxAppender(OutboxPort outbox, ObjectMapper json, CurrentUserAccessor currentUser) {
        this.outbox = outbox;
        this.json = json;
        this.currentUser = currentUser;
    }

    /**
     * Serialise {@code event} and append it as a {@code 'pending'} outbox row,
     * stamping the <em>current</em> authenticated user as the actor — resolves
     * to null on saga-worker / inbox threads that run outside an HTTP request.
     * Use this from command-driven callers; the outbox message id is the event id.
     */
    public void append(DomainEvent event, String aggregateType) {
        append(event, aggregateType, currentUser.currentUsername().orElse(null));
    }

    /**
     * As {@link #append(DomainEvent, String)} but with an explicit actor —
     * used by inbox handlers that propagate the inbound envelope's actor
     * through the saga chain ({@code envelope.actorUserId()}), since the Kafka
     * consumer thread has no {@code SecurityContext} to read.
     */
    public void append(DomainEvent event, String aggregateType, String actorUserId) {
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                aggregateType,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                OutboxTraceHeaders.currentJson(), null, null,
                actorUserId
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise " + event.eventType(), e);
        }
    }
}
