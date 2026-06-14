package com.northwood.shared.application.messaging;

import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.inbox.InboxRow;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Common boilerplate for inbox handlers — every concrete handler in the
 * codebase shares the same shape: dedupe via inbox, deserialise the payload,
 * apply the side effects, record the inbox row. Subclasses pass their event
 * type and consumer name through the constructor and implement
 * {@link #apply(Object, EventEnvelope)}; nothing else.
 *
 * <p>None of {@link #handle}, {@link #handles}, or {@link #handlerName} are
 * declared {@code final}, even though the latter two have no AOP advice on
 * their own. The reason is the {@code @Transactional} annotation on
 * {@code handle()}: it triggers Spring to create a CGLIB proxy for the whole
 * class, which Objenesis instantiates without invoking the constructor — so
 * every instance field on the proxy is null. CGLIB cannot override final
 * methods, so any final method runs on the proxy with {@code this.eventType}
 * / {@code this.handlerName} = null and NPEs on the first call.
 * Spring Kafka wraps the NPE in {@code ListenerExecutionFailedException} and
 * the bounded-retry path eventually publishes the message to the
 * {@code <topic>.dlt} dead-letter topic — silently unless DLT topics are
 * being watched. Leaving the methods non-final lets CGLIB generate overrides
 * that delegate to the constructor-initialised target instance, where the
 * fields resolve correctly. The "contract is fixed at construction" intent
 * is preserved by convention — concrete handlers don't override these methods.
 *
 * @param <P> the concrete payload class to deserialise the envelope into
 */
public abstract class AbstractInboxHandler<P> implements InboxEnvelopeHandler {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final InboxPort inbox;
    protected final ObjectMapper json;
    private final Class<P> payloadType;
    private final String eventType;
    private final String handlerName;

    protected AbstractInboxHandler(
        InboxPort inbox,
        ObjectMapper json,
        Class<P> payloadType,
        String eventType,
        String handlerName
    ) {
        this.inbox = inbox;
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
    @Transactional
    public void handle(EventEnvelope envelope) {
        if (!handles(envelope.eventType())) {
            return;
        }
        if (inbox.alreadyProcessed(envelope.eventId(), handlerName)) {
            log.debug("[{}] skipping already-processed {} ({})",
                handlerName, envelope.eventType(), envelope.eventId());
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

        inbox.recordProcessed(InboxRow.processed(
            UUID.randomUUID(),
            envelope.eventId(),
            handlerName,
            envelope.eventType(),
            envelope.eventVersion(),
            null,
            envelope.payloadJson()
        ));
    }

    /** Apply the deserialised payload. Runs inside the {@code @Transactional} boundary opened by {@link #handle}. */
    protected abstract void apply(P payload, EventEnvelope envelope);
}
