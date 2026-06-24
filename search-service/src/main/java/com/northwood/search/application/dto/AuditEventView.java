package com.northwood.search.application.dto;

import java.time.Instant;

/**
 * One audit hit — a single projected event with its routing/idempotency metadata
 * and raw {@code payload}. Returned newest-first by the audit search.
 */
public record AuditEventView(
    String eventId,
    String eventType,
    String aggregateType,
    String aggregateId,
    String sourceService,
    String actorUserId,
    String correlationId,
    Instant occurredAt,
    String payload
) {}
