package com.northwood.sales.application.dto;

import com.northwood.sales.domain.SalesOrder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link SalesOrder} for the wire layer. */
public record SalesOrderView(
    UUID id,
    String orderNumber,
    UUID customerId,
    String customerCode,
    String customerName,
    LocalDate orderDate,
    LocalDate requestedDeliveryDate,
    String status,
    String currencyCode,
    BigDecimal subtotalAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String paymentTerms,
    long version,
    /** When cancellation was first requested (phase 1); null if never. */
    Instant cancellationRequestedAt,
    /**
     * Derived two-phase-cancel label: {@code none} / {@code cancelling} /
     * {@code cancelled} / {@code cancellation_rejected}. Lets the UI reconcile an
     * optimistic "Cancellation requested…" state to a terminal by polling.
     */
    String cancellationOutcome,
    List<SalesOrderLineView> lines
) {
    public static SalesOrderView from(SalesOrder order) {
        return new SalesOrderView(
            order.id().value(),
            order.orderNumber(),
            order.customerId(),
            order.customerCode(),
            order.customerName(),
            order.orderDate(),
            order.requestedDeliveryDate(),
            order.status().code(),
            order.currencyCode(),
            order.subtotalAmount(),
            order.taxAmount(),
            order.totalAmount(),
            order.paymentTerms().code(),
            order.version(),
            order.cancellationRequestedAt(),
            order.cancellationOutcome().code(),
            order.lines().stream().map(SalesOrderLineView::from).toList()
        );
    }
}
