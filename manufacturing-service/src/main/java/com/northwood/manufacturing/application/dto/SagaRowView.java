package com.northwood.manufacturing.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Application-layer view of one saga row for the Saga Console. Read-side
 * mirror of {@code api.dto.SagaRow} (the wire DTO) — fields identical, but
 * placement in {@code application/dto/} keeps the application + infrastructure
 * layers free of api-layer imports per the hexagonal layering rule. The
 * controller maps to {@code SagaRow} at the boundary.
 */
public record SagaRowView(
    UUID sagaId,
    UUID domainKey,
    String domainKeyLabel,
    String sagaType,
    String state,
    String currentStep,
    String lastError,
    int retryCount,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt
) {}