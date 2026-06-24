package com.northwood.reporting.infrastructure.cache;

/**
 * Cache-name constants shared between the {@code @Cacheable} read methods on the
 * {@code Jdbc*QueryPort}s and the {@link ReportingCacheEvictor} write-side
 * eviction. Both sides reference the same constant so a rename can never let a
 * read and its eviction drift onto different cache names — a typo'd literal on
 * either side would otherwise leak stale entries until the TTL backstop expired.
 *
 * <p>Each name maps to one read model:
 * <ul>
 *   <li>{@link #ATP} — keyed by {@code productId} ({@code AvailableToPromiseQueryPort.findByProductId}).</li>
 *   <li>{@link #FINANCIAL_DASHBOARD} — keyed by {@code currencyCode}
 *       ({@code FinancialDashboardQueryPort.findSnapshot}); evicted whole (few keys).</li>
 *   <li>{@link #SALES_ORDER_360} — keyed by {@code salesOrderHeaderId}
 *       ({@code SalesOrder360QueryPort.findBySalesOrderId}).</li>
 * </ul>
 */
public final class ReportingCacheNames {

    public static final String ATP = "atp";
    public static final String FINANCIAL_DASHBOARD = "financial-dashboard";
    public static final String SALES_ORDER_360 = "sales-order-360";

    private ReportingCacheNames() {
    }
}
