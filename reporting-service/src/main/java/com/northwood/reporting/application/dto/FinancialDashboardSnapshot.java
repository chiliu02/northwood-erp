package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * As-of-now totals computed via SUM-window over reporting's local
 * projections, served alongside the per-day-delta rows in
 * {@link FinancialDashboardView}. Distinct shape because the delta rows are
 * by-date and the snapshot is point-in-time.
 *
 * <p>One column from the original dashboard design is still parked:
 * {@code wipValue} (gated on a costing decision — LIFO / FIFO /
 * weighted-avg). It lives on {@link FinancialDashboardView} as 0 today.
 */
public record FinancialDashboardSnapshot(
    String currencyCode,
    BigDecimal accountsReceivable,
    BigDecimal accountsPayable,
    BigDecimal inventoryValue,
    int openSalesOrdersCount,
    int openPurchaseOrdersCount,
    int openWorkOrdersCount,
    Instant asOf
) {}
