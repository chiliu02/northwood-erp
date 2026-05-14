package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory's reply to {@code sales.StockReservationRequested}. The
 * {@code status} field carries header-level outcome:
 * {@code reserved | partially_reserved | failed}. Per-line detail is on
 * {@link ReservedLine}.
 *
 * <p>The {@code aggregateId} is the reservation's id, which Kafka will use to
 * key the event onto a partition. {@code salesOrderId} is what the consuming
 * saga keys off.
 */
public record StockReserved(
    UUID eventId,
    UUID aggregateId,
    UUID salesOrderId,
    UUID stockReservationId,
    String status,
    List<ReservedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.StockReserved";

    /** Wire-format values of {@link #status} — the reservation outcome at header and line level. */
    public static final String STATUS_RESERVED = "reserved";
    public static final String STATUS_PARTIALLY_RESERVED = "partially_reserved";
    public static final String STATUS_FAILED = "failed";

    @Override public String eventType() { return EVENT_TYPE; }

    public record ReservedLine(
        int lineNumber,
        UUID productId,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal shortageQuantity,
        String status
    ) {}
}
