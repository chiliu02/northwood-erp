package com.northwood.manufacturing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Raw-material reservation came back as {@code partially_reserved} or
 * {@code failed} for a work order, and the make-to-order saga has parked at
 * {@code raw_material_shortage}. Carries the unfulfilled components so
 * purchasing can spawn a requisition for the missing quantities.
 *
 * <p>Cross-context contract: purchasing's {@code RawMaterialShortageDetectedHandler}
 * consumes this and creates a {@code purchase_requisition} with
 * {@code source_type='work_order_shortage'} pointing back at the work order.
 *
 * <p>{@code aggregateId} is the work-order id; partition key for sale-order-line
 * scoping isn't useful here because the shortage is per work order (and a
 * work order belongs to exactly one sales-order line).
 */
public record RawMaterialShortageDetected(
    UUID eventId,
    UUID aggregateId,
    UUID workOrderId,
    UUID salesOrderHeaderId,
    UUID salesOrderLineId,
    String warehouseCode,
    List<ShortageComponent> components,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "manufacturing.RawMaterialShortageDetected";

    @Override public String eventType() { return EVENT_TYPE; }

    public record ShortageComponent(
        UUID workOrderMaterialId,
        UUID componentProductId,
        String componentSku,
        String componentName,
        BigDecimal shortageQuantity
    ) {}
}
