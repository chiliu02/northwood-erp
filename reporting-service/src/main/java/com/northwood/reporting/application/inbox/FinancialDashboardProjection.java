package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Maintains {@code reporting.financial_dashboard_daily}. One row per
 * (dashboard_date, currency_code) — a per-day delta view (every column is
 * "what happened on this date" rather than an end-of-day balance snapshot).
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcFinancialDashboardProjection}.
 */
public interface FinancialDashboardProjection {

    void recordCustomerInvoice(BigDecimal amount, String currencyCode, Instant occurredAt);

    void recordSupplierInvoice(BigDecimal amount, String currencyCode, Instant occurredAt);

    void recordCustomerPayment(BigDecimal amount, String currencyCode, Instant occurredAt);

    void recordSupplierPayment(BigDecimal amount, String currencyCode, Instant occurredAt);

    void recordSalesOrderPlaced(String currencyCode, Instant occurredAt);

    void recordPurchaseOrderCreated(String currencyCode, Instant occurredAt);

    void recordWorkOrderCreated(String currencyCode, Instant occurredAt);
}
