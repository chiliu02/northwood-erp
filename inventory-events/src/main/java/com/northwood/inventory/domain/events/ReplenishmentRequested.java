package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * §2.35 Slice B: a replenishment has been requested for a SKU at a warehouse.
 * Raised by inventory's reorder-point detection service (on-hand decrement
 * paths) and by inventory's shortage-to-replenishment bridge (Slice C, driven
 * by {@code manufacturing.RawMaterialShortageDetected}).
 *
 * <p>Routed downstream by {@code targetService}: manufacturing's
 * {@code ReplenishmentRequestedHandler} releases a stock work order;
 * purchasing's {@code ReplenishmentRequestedHandler} creates a purchase
 * requisition with {@code source_type='stock_replenishment'}. Manufacturing
 * and purchasing each filter on their own value and ignore the other.
 *
 * <p>{@code aggregateId} is the replenishment_request_id.
 *
 * <p>Cross-service wire-format constants live on this class because consumers
 * in other services can't import inventory's domain enums (schema-per-service
 * rule).
 */
public record ReplenishmentRequested(
    UUID eventId,
    UUID aggregateId,
    UUID productId,
    UUID warehouseId,
    BigDecimal quantity,
    String targetService,
    String reason,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.ReplenishmentRequested";

    public static final String TARGET_SERVICE_MANUFACTURING = "manufacturing";
    public static final String TARGET_SERVICE_PURCHASING = "purchasing";

    public static final String REASON_REORDER_POINT_BREACH = "reorder_point_breach";
    public static final String REASON_WORK_ORDER_SHORTAGE = "work_order_shortage";

    @Override public String eventType() { return EVENT_TYPE; }
}
