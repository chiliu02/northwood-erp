package com.northwood.shared.application.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire-shape for one row in a service's audit timeline. One per outbox event.
 * The {@code sourceService} field is populated by the BFF aggregator on the
 * way out so a merged timeline can label which service emitted each row.
 *
 * <p>{@code traceId} is the W3C trace ID extracted from
 * {@code outbox_message.headers->>'traceparent'} (set by {@code OutboxPublisher}).
 * Surfaced here so the SPA audit / event log can render a
 * {@code ↗ trace} affordance per row without joining or re-parsing the headers
 * JSONB on the client. Nullable: rows written before trace capture was wired,
 * or in test runs where no Micrometer Tracer was active, carry null.
 */
public record AuditEntry(
    UUID outboxMessageId,
    Long sequenceNumber,
    String sourceService,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    String actorUserId,
    String correlationId,
    String traceId,
    Instant occurredAt
) {}
