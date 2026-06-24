package com.northwood.search.application.inbox;

import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Boilerplate for the Elasticsearch projectors — the search-side analogue of
 * {@code shared.AbstractInboxHandler}, minus the Postgres inbox gate.
 *
 * <p>search-service owns no Postgres, so there is no {@code inbox_message} table
 * to dedupe against. It doesn't need one: every projection write is an
 * <strong>upsert keyed by the aggregate id</strong>, so an at-least-once
 * redelivery re-applies the same document with the same effect. Subclasses pass
 * their event type + handler name and implement {@link #apply}; the base
 * deserialises the payload and dispatches.
 *
 * @param <P> the concrete event payload class to deserialise the envelope into
 */
public abstract class AbstractEventProjectionHandler<P> implements InboxEnvelopeHandler {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ObjectMapper json;
    private final Class<P> payloadType;
    private final String eventType;
    private final String handlerName;

    protected AbstractEventProjectionHandler(
        ObjectMapper json,
        Class<P> payloadType,
        String eventType,
        String handlerName
    ) {
        this.json = json;
        this.payloadType = payloadType;
        this.eventType = eventType;
        this.handlerName = handlerName;
    }

    @Override
    public boolean handles(String eventType) {
        return this.eventType.equals(eventType);
    }

    @Override
    public String handlerName() {
        return handlerName;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        if (!handles(envelope.eventType())) {
            return;
        }
        P payload;
        try {
            payload = json.readValue(envelope.payloadJson(), payloadType);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                "Failed to deserialise " + envelope.eventType() + " " + envelope.eventId(), e
            );
        }
        apply(payload, envelope);
    }

    /** Project the deserialised payload into Elasticsearch — idempotently (upsert by id). */
    protected abstract void apply(P payload, EventEnvelope envelope);
}
