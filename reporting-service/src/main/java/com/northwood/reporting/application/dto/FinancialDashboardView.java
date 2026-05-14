package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record FinancialDashboardView(
    LocalDate dashboardDate,
    String currencyCode,
    BigDecimal salesRevenue,
    BigDecimal costOfGoodsSold,
    BigDecimal grossProfit,
    BigDecimal inventoryValue,
    BigDecimal wipValue,
    BigDecimal accountsReceivable,
    BigDecimal accountsPayable,
    BigDecimal cashReceived,
    BigDecimal cashPaid,
    int openSalesOrdersCount,
    int openPurchaseOrdersCount,
    int openWorkOrdersCount,
    Instant updatedAt
) {}