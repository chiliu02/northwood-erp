package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inventory's reply to {@code manufacturing.RawMaterialReservationRequested}.
 * Parallel to {@code inventory.StockReserved} for finished goods, but keyed
 * on {@code workOrderId} instead of {@code salesOrderId}. Two events instead
 * of one keeps the cross-context contract per source-aggregate type clean —
 * the sales-side saga never needs to inspect a discriminator.
 */
public record RawMaterialsReserved(
    UUID eventId,
    UUID aggregateId,
    UUID workOrderId,
    UUID stockReservationId,
    String status,
    List<ReservedComponent> components,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.RawMaterialsReserved";

    /** Wire-format values of {@link #status} — the reservation outcome at header and line level. */
    public static final String STATUS_RESERVED = "reserved";
    public static final String STATUS_PARTIALLY_RESERVED = "partially_reserved";
    public static final String STATUS_FAILED = "failed";

    @Override public String eventType() { return EVENT_TYPE; }

    public record ReservedComponent(
        UUID workOrderMaterialId,
        UUID componentProductId,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal shortageQuantity,
        String status
    ) {}
}
