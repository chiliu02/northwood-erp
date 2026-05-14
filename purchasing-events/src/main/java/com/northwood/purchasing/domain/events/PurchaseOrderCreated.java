package com.northwood.purchasing.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A purchase order has been created (auto-converted from a requisition in
 * phase 2; manual creation will follow). Carries enough detail for the
 * goods-receipt flow (phase 3) and any future supplier-facing notification.
 *
 * <p>{@code sourceWorkOrderId} is non-null only when the originating PR was
 * shortage-driven (PR.source_type='work_order_shortage'). Reporting projects
 * it onto {@code purchase_order_tracking_view.source_work_order_id} so the
 * production-planning-board can compute {@code open_purchase_orders_count}
 * per WO. Existing consumers that don't read the field can ignore it
 * (Jackson 3 default deserialisation tolerates unknown fields).
 */
public record PurchaseOrderCreated(
    UUID eventId,
    UUID aggregateId,
    String purchaseOrderNumber,
    UUID supplierId,
    String supplierCode,
    String supplierName,
    UUID purchaseRequisitionHeaderId,
    UUID sourceWorkOrderId,
    String currencyCode,
    BigDecimal totalAmount,
    String status,
    List<OrderLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "purchasing.PurchaseOrderCreated";

    @Override public String eventType() { return EVENT_TYPE; }

    public record OrderLine(
        UUID lineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice
    ) {}
}
