package com.northwood.sales.application.dto;

import com.northwood.sales.domain.SalesOrder;
import java.math.BigDecimal;
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
    long version,
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
            order.status().dbValue(),
            order.currencyCode(),
            order.subtotalAmount(),
            order.taxAmount(),
            order.totalAmount(),
            order.version(),
            order.lines().stream().map(SalesOrderLineView::from).toList()
        );
    }
}
