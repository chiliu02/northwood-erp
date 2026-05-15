package com.northwood.reporting.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * As-of-now totals computed via SUM-window over reporting's local
 * projections, served alongside the per-day-balance rows in
 * {@link FinancialDashboardView}. Distinct shape because the per-day rows
 * are by-date and the snapshot is point-in-time.
 *
 * <p>{@code wipValue} is wire-shape-uniform with {@link FinancialDashboardView}
 * but always {@code 0} today — gated on a costing decision (LIFO / FIFO /
 * weighted-avg) for {@code manufacturing.wip_balance.average_cost}. Keep the
 * field present so consumers don't have to special-case its absence; flip to
 * a real computation when costing lands.
 */
public record FinancialDashboardSnapshot(
    String currencyCode,
    BigDecimal accountsReceivable,
    BigDecimal accountsPayable,
    BigDecimal inventoryValue,
    BigDecimal wipValue,
    int openSalesOrdersCount,
    int openPurchaseOrdersCount,
    int openWorkOrdersCount,
    Instant asOf
) {}
