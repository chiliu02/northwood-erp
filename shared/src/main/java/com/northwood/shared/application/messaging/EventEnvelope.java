package com.northwood.shared.application.messaging;

import com.northwood.shared.domain.Assert;
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
     * {@code "sales"}). Set by {@code OutboxDrainer} when draining a row,
     * read by {@code KafkaEventPublisher} to derive the topic
     * ({@code <source-service>.events}). The reader fails fast if it is
     * missing or blank.
     */
    public static final String HEADER_SOURCE_SERVICE = "source-service";

    /**
     * W3C trace-context header carrying the current trace's
     * {@code 00-<traceId>-<spanId>-<flags>} string. Stamped by
     * {@code OutboxPublisher} inside the per-row Micrometer observation so it
     * captures the publish-side span. Read by the BFF events aggregator
     * to surface a trace-drilldown affordance in the SPA Event Log / Saga
     * Console — without that consumer having to crack the Kafka record headers
     * separately. The corresponding header on the Kafka record itself is
     * stamped by Spring Kafka's observation-enabled producer.
     */
    public static final String HEADER_TRACEPARENT = "traceparent";

    public EventEnvelope {
        Assert.notNull(eventId, "eventId");
        Assert.notBlank(aggregateType, "aggregateType");
        Assert.notNull(aggregateId, "aggregateId");
        Assert.notBlank(eventType, "eventType");
        Assert.notNull(payloadJson, "payloadJson");
        if (headers == null) headers = Map.of();
        if (occurredAt == null) occurredAt = Instant.now();
    }
}
