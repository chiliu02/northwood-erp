package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Saga-driven request to manufacturing: release work orders for the
 * manufactured lines of a sales order. Manufacturing decides per-line whether
 * an active BOM exists; lines without one are skipped (they're direct-ship).
 *
 * <p>Lives on the sales-service outbox, so manufacturing receives it via its
 * normal Kafka inbox dispatch.
 */
public record ManufacturingRequested(
    UUID eventId,
    UUID aggregateId,
    UUID salesOrderHeaderId,
    List<RequestedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.ManufacturingRequested";

    @Override public String eventType() { return EVENT_TYPE; }

    public record RequestedLine(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity
    ) {}
}
