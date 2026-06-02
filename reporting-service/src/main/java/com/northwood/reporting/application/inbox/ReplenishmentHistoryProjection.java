package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maintains {@code reporting.replenishment_history_view}. One
 * row per {@code inventory.ReplenishmentRequest}, advanced by the four
 * replenishment lifecycle events.
 *
 * <p>Order-tolerant by design: events arrive on independent Kafka topics
 * (inventory + manufacturing + purchasing), and even within one producer's
 * outbox they may land on different partitions. Each write is an upsert keyed
 * on {@code replenishment_request_id}; status / timestamp transitions are
 * write-forward (a late "requested" insert doesn't regress a row that's
 * already advanced past it).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcReplenishmentHistoryProjection}.
 */
public interface ReplenishmentHistoryProjection {

    void recordRequested(
        UUID replenishmentRequestId,
        UUID productId,
        UUID warehouseId,
        BigDecimal requestedQuantity,
        String targetService,
        String reason,
        Instant requestedAt
    );

    void recordDispatched(
        UUID replenishmentRequestId,
        String dispatchedAggregateKind,
        UUID dispatchedAggregateId,
        Instant dispatchedAt
    );

    void recordFulfilled(
        UUID replenishmentRequestId,
        Instant fulfilledAt
    );
}
