package com.northwood.search.application.inbox;

import com.northwood.search.application.AuditEventProjection;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.InboxEnvelopeHandler;
import org.springframework.stereotype.Component;

/**
 * The read-model-C catch-all projector: appends <em>every</em> event to the
 * {@code audit-events} index. Unlike the other projectors it doesn't filter to
 * one event type ({@link #handles} returns true for all) and doesn't deserialise
 * the payload — it stores the raw envelope. Idempotent via the projection's
 * upsert-by-event-id.
 */
@Component
public class AuditEventHandler implements InboxEnvelopeHandler {

    public static final String HANDLER_NAME = "search.audit.all-events";

    private final AuditEventProjection auditEvents;

    public AuditEventHandler(AuditEventProjection auditEvents) {
        this.auditEvents = auditEvents;
    }

    @Override
    public boolean handles(String eventType) {
        return true;
    }

    @Override
    public String handlerName() {
        return HANDLER_NAME;
    }

    @Override
    public void handle(EventEnvelope envelope) {
        auditEvents.append(envelope);
    }
}
