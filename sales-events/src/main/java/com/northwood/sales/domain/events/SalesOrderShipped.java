package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The goods for a sales order have shipped. Sales emits this from its
 * fulfilment-saga shipment handler — finance consumes it and auto-creates
 * the customer invoice. Carries the rich per-line data finance needs
 * (pricing, tax) which {@code inventory.ShipmentPosted} doesn't carry
 * (inventory doesn't own pricing).
 *
 * <p>{@code aggregateId} is the sales-order-header id.
 *
 * <p>{@code paymentTerms} is the order's commercial terms — a
 * {@link com.northwood.sales.domain.PaymentTerms} {@code code()}. Finance
 * branches on it at shipment: a {@code cash_on_delivery} order has its customer
 * payment auto-recorded the moment the invoice is created (cash-on-delivery). Nullable for
 * backward compatibility with in-flight messages produced by older versions —
 * consumers treat null as {@code on_shipment}.
 *
 * <p>{@code orderFullyShipped} is {@code true} when this shipment completes the
 * order (every line's cumulative shipped quantity now meets its ordered
 * quantity), {@code false} for a partial shipment that leaves a backorder. Only
 * sales can decide this — it owns ordered vs. cumulative-shipped quantities.
 * Reporting uses it to show {@code shipped} vs. {@code partially_shipped}; the
 * fulfilment saga uses its in-process equivalent to gate {@code goods_shipped}.
 * A primitive {@code boolean} defaults to {@code false} when an older in-flight
 * message lacks it — the safe default (hold as partial rather than wrongly
 * complete).
 */
public record SalesOrderShipped(
    UUID eventId,
    UUID aggregateId,
    String orderNumber,
    UUID shipmentHeaderId,
    String shipmentNumber,
    UUID customerId,
    String customerCode,
    String customerName,
    LocalDate shipmentDate,
    String currencyCode,
    String paymentTerms,
    List<ShippedLine> lines,
    boolean orderFullyShipped,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderShipped";

    @Override public String eventType() { return EVENT_TYPE; }

    public record ShippedLine(
        UUID salesOrderLineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal shippedQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate,
        BigDecimal unitCost
    ) {}
}
