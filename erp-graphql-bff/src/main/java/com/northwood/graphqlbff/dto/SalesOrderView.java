package com.northwood.graphqlbff.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Mirror of sales-service {@code SalesOrderView} (GET /api/sales-orders/{id}).
 * Backs the GraphQL {@code SalesOrder} type; {@code customer} is resolved from
 * {@link #customerId()} via a batch loader, {@code lines} is the embedded list.
 * Locally mirrored — the BFF keeps no Maven dependency on the service modules.
 */
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
    Instant cancellationRequestedAt,
    String cancellationOutcome,
    List<SalesOrderLineView> lines
) {}
