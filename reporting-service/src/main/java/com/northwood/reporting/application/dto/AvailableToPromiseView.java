package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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