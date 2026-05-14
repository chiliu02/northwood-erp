package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manufacturing's request to inventory: reserve the BOM components needed to
 * build a work order. {@code aggregateId} is the work order id (so events for
 * the same WO are partitioned together on Kafka).
 */
public record RawMaterialReservationRequested(
    UUID eventId,
    UUID aggregateId,
    UUID workOrderId,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    String warehouseCode,
    List<RequestedComponent> components,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.RawMaterialReservationRequested";

    @Override public String eventType() { return EVENT_TYPE; }

    public record RequestedComponent(
        UUID workOrderMaterialId,
        UUID componentProductId,
        String componentSku,
        String componentName,
        BigDecimal requiredQuantity
    ) {}
}
