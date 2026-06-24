package com.northwood.graphqlbff.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mirror of reporting-service {@code SalesOrder360View} (GET /api/sales-orders).
 * Backs the GraphQL {@code SalesOrderSummary} type (the list view, no lines).
 */
public record SalesOrder360View(
    UUID salesOrderHeaderId,
    String orderNumber,
    UUID customerId,
    String customerName,
    LocalDate orderDate,
    LocalDate requestedDeliveryDate,
    String orderStatus,
    String stockStatus,
    String manufacturingStatus,
    String shipmentStatus,
    String invoiceStatus,
    String paymentStatus,
    String paymentTerms,
    String currencyCode,
    BigDecimal totalAmount,
    BigDecimal invoicedAmount,
    BigDecimal paidAmount,
    BigDecimal outstandingAmount,
    boolean hasShortage,
    String shortageSummary,
    String lastEventType,
    Instant lastEventAt,
    Instant updatedAt
) {}
