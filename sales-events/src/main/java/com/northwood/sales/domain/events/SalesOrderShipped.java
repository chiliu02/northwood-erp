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
 * {@link com.northwood.sales.domain.PaymentTerms} {@code dbValue()}. Finance
 * branches on it at shipment: a {@code cash_on_delivery} order has its customer
 * payment auto-recorded the moment the invoice is created (cash-on-delivery). Nullable for
 * backward compatibility with in-flight messages produced by older versions —
 * consumers treat null as {@code on_shipment}.
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
        BigDecimal taxRate
    ) {}
}
