package com.northwood.graphqlbff.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mirror of reporting-service {@code AvailableToPromiseView} (GET /api/atp).
 * Backs the GraphQL {@code AvailableToPromise} type, indexed by
 * {@link #productId()}.
 */
public record AvailableToPromiseView(
    UUID productId,
    String productSku,
    String productName,
    BigDecimal onHandQuantity,
    BigDecimal reservedForSales,
    BigDecimal reservedForProduction,
    BigDecimal availableQuantity,
    BigDecimal incomingFromProduction,
    BigDecimal incomingFromPurchase,
    LocalDate earliestAvailableDate,
    String stockStatus,
    Instant updatedAt
) {}
