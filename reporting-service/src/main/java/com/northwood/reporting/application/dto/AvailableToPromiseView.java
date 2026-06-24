package com.northwood.reporting.application.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Serializable so the read-side Redis cache can store it (Spring's default
// Redis cache uses JDK value serialization) — see ReportingCacheConfig.
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
) implements Serializable {}