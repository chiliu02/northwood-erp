package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side view of one row in the replenishment history.
 * SKU + product name are joined at query time from
 * {@code reporting.available_to_promise_view} (which already carries them
 * keyed by product_id). Null when the join finds no matching ATP row
 * (typically a SKU never sold or stocked elsewhere; stays null-safe).
 */
public record ReplenishmentHistoryView(
    UUID replenishmentRequestId,
    UUID productId,
    String productSku,
    String productName,
    UUID warehouseId,
    BigDecimal requestedQuantity,
    String targetService,
    String reason,
    String status,
    String dispatchedAggregateKind,
    UUID dispatchedAggregateId,
    Instant requestedAt,
    Instant dispatchedAt,
    Instant fulfilledAt
) {}
