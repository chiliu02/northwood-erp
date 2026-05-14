package com.northwood.shared.application.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire-shape for one row in a service's audit timeline. One per outbox event.
 * The {@code sourceService} field is populated by the BFF aggregator on the
 * way out so a merged timeline can label which service emitted each row.
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
    Instant occurredAt
) {}
