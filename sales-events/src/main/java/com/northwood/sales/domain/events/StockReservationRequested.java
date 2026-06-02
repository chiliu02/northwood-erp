package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Saga-driven request to inventory: reserve stock for the named lines of a
 * sales order. Inventory consumes this on its inbox and replies with
 * {@code inventory.StockReserved} or {@code inventory.StockReservationFailed}.
 *
 * <p>Although the saga is the orchestrator, this event is published through
 * the standard outbox so it gets the same delivery guarantees as any other
 * fact published by sales-service.
 */
public record StockReservationRequested(
    UUID eventId,
    UUID aggregateId,
    UUID salesOrderId,
    String warehouseCode,
    List<RequestedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.StockReservationRequested";

    @Override public String eventType() { return EVENT_TYPE; }

    /**
     * @param pegged order-pegged ({@code to_order}): inventory must NOT
     *     reserve this line from the shared pool — it raises dedicated supply for
     *     the full quantity, earmarked to the SO line. {@code false} = today's
     *     make/buy-to-stock free-stock reservation path.
     */
    public record RequestedLine(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requestedQuantity,
        boolean pegged
    ) {}
}
