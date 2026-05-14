package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MaterialShortageView(
    UUID materialProductId,
    String materialSku,
    String materialName,
    BigDecimal requiredQuantity,
    BigDecimal availableQuantity,
    BigDecimal shortageQuantity,
    int affectedWorkOrdersCount,
    int affectedSalesOrdersCount,
    int openPurchaseOrdersCount,
    BigDecimal incomingPurchaseQuantity,
    LocalDate expectedReceiptDate,
    String status,
    Instant updatedAt
) {}