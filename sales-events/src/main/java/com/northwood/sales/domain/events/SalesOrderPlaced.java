package com.northwood.sales.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sales order has been accepted and persisted. Carries enough denormalised
 * shape that downstream projections (the 360 view, reporting) can build a
 * read model without joining back across schemas.
 *
 * <p>This event is the business fact that the order was placed. The saga's
 * orchestration request to inventory rides on a separate
 * {@link StockReservationRequested} event.
 *
 * <p>{@code paymentTerms} is the wire-format value of the commercial terms
 * snapshotted from the customer at placement (overridable per order). One of
 * {@link #PAYMENT_TERMS_ON_SHIPMENT} or {@link #PAYMENT_TERMS_PREPAYMENT};
 * nullable for backward compatibility with in-flight messages produced before
 * §2.31 — consumers treat a null as {@code on_shipment}. Slice A (foundation)
 * just snapshots and projects the value; Slice B+ hangs the saga branch + GL
 * routing on it.
 */
public record SalesOrderPlaced(
    UUID eventId,
    UUID aggregateId,
    String orderNumber,
    UUID customerId,
    String customerCode,
    String customerName,
    String currencyCode,
    BigDecimal totalAmount,
    String paymentTerms,
    List<PlacedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderPlaced";

    /** {@code paymentTerms} wire value — credit terms; invoice on shipment (default). */
    public static final String PAYMENT_TERMS_ON_SHIPMENT = "on_shipment";
    /** {@code paymentTerms} wire value — cash with order; invoice at placement, shipment gated on payment. */
    public static final String PAYMENT_TERMS_PREPAYMENT = "prepayment";

    @Override public String eventType() { return EVENT_TYPE; }

    public record PlacedLine(
        UUID lineId,
        int lineNumber,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal orderedQuantity,
        BigDecimal unitPrice
    ) {}
}
