package com.northwood.search.infrastructure.elasticsearch;

import com.northwood.search.application.AuditEventProjection;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.UUID;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Repository;

/**
 * Elasticsearch implementation of {@link AuditEventProjection}. Saves one
 * {@link AuditEventDocument} per event keyed by the event id, so a re-delivery is
 * an idempotent overwrite. {@code sourceService} comes from the envelope's
 * {@code source-service} header (the producer that emitted it).
 */
@Repository
public class ElasticsearchAuditEventProjection implements AuditEventProjection {

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchAuditEventProjection(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public void append(EventEnvelope envelope) {
        AuditEventDocument doc = new AuditEventDocument(
            envelope.eventId().toString(),
            envelope.eventType(),
            envelope.aggregateType(),
            idToString(envelope.aggregateId()),
            envelope.eventVersion(),
            envelope.headers().get(EventEnvelope.HEADER_SOURCE_SERVICE),
            envelope.actorUserId(),
            idToString(envelope.correlationId()),
            envelope.occurredAt().toEpochMilli(),
            envelope.payloadJson()
        );
        elasticsearch.save(doc);
    }

    private static String idToString(UUID id) {
        return id == null ? null : id.toString();
    }
}
