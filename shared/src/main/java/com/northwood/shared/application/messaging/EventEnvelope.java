package com.northwood.shared.application.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Serialised form of a domain event as it travels through the outbox, the bus,
 * and the consumer's inbox. The event payload itself is JSON inside
 * {@code payload}; envelope fields carry the routing/idempotency metadata.
 *
 * <p>Maps directly to the {@code outbox_message} columns: aggregate_type,
 * aggregate_id, event_type, event_version, payload, headers, correlation_id,
 * causation_id, actor_user_id.
 *
 * <p>{@code actorUserId} is the Keycloak {@code preferred_username} of the
 * user whose action emitted the event (Slice B2). Nullable: saga-driven
 * events created without an HTTP request context (outbox publisher,
 * polling worker, GL-posting side effects) carry null. Reporting projections
 * read this field and project it as {@code last_modified_by}; consumers
 * that don't care can ignore it (additive field — no version bump needed).
 */
public record EventEnvelope(
    UUID eventId,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    int eventVersion,
    String payloadJson,
    Map<String, String> headers,
    UUID correlationId,
    UUID causationId,
    String actorUserId,
    Instant occurredAt
) {
    /**
     * Header carrying the originating service name (e.g. {@code "product"},
     * {@code "sales"}). Set by {@code OutboxPublisher} when draining a row,
     * read by {@code KafkaEventPublisher} to derive the topic
     * ({@code <source-service>.events}). The reader fails fast if it is
     * missing or blank.
     */
    public static final String HEADER_SOURCE_SERVICE = "source-service";

    public EventEnvelope {
        if (eventId == null) throw new IllegalArgumentException("eventId");
        if (aggregateType == null || aggregateType.isBlank())
            throw new IllegalArgumentException("aggregateType");
        if (aggregateId == null) throw new IllegalArgumentException("aggregateId");
        if (eventType == null || eventType.isBlank())
            throw new IllegalArgumentException("eventType");
        if (payloadJson == null) throw new IllegalArgumentException("payloadJson");
        if (headers == null) headers = Map.of();
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
