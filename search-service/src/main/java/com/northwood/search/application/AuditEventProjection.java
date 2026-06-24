package com.northwood.search.application;

import com.northwood.shared.application.messaging.EventEnvelope;

/**
 * Write side of the {@code audit-events} read model (read model C). The
 * catch-all projector appends every {@link EventEnvelope} it sees. Idempotent —
 * the document id is the event id, so a re-delivery overwrites the same doc. The
 * Elasticsearch implementation lives in {@code infrastructure/elasticsearch/}.
 */
public interface AuditEventProjection {

    /** Append (idempotent upsert by event id) one event to the audit index. */
    void append(EventEnvelope envelope);
}
