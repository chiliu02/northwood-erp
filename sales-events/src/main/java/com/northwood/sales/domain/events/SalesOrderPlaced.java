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
 * snapshotted from the customer at placement (overridable per order) — a
 * {@link com.northwood.sales.domain.PaymentTerms} {@code dbValue()}. Nullable
 * for backward compatibility with in-flight messages produced by older versions —
 * consumers treat a null as {@code on_shipment}.
 *
 * <p>{@code depositPercent} is the up-front fraction (0–100) for
 * {@code deposit} orders — non-null only when {@code paymentTerms = 'deposit'};
 * null for every other term.
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
    BigDecimal depositPercent,
    List<PlacedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "sales.SalesOrderPlaced";

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
