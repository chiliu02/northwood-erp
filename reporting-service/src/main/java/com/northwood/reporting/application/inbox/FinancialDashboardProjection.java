package com.northwood.reporting.application.inbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Maintains {@code reporting.financial_dashboard_daily}. One row per
 * {@code (dashboard_date, currency_code)} with a hybrid shape:
 *
 * <ul>
 *   <li><b>Flow columns</b> ({@code sales_revenue}, {@code cost_of_goods_sold},
 *       {@code gross_profit}, {@code cash_received}, {@code cash_paid}) —
 *       per-day deltas, written incrementally by the {@code record*} methods
 *       below. Each method bumps the column on the row matching the event's
 *       {@code occurredAt::date} via {@code INSERT ... ON CONFLICT DO UPDATE
 *       SET COL = COL + delta}.</li>
 *   <li><b>Balance columns</b> ({@code accounts_receivable},
 *       {@code accounts_payable}, {@code inventory_value}, {@code wip_value},
 *       {@code open_sales_orders_count}, {@code open_purchase_orders_count},
 *       {@code open_work_orders_count}) — point-in-time balances, overwritten
 *       periodically by {@link #refreshDailyBalances} via SUM-window over
 *       reporting's local projections. Daniel reads "AR as of date X" by
 *       fetching the row for that date; freshness = worker tick cadence.</li>
 * </ul>
 *
 * <p>Why hybrid: flows are naturally additive (an invoice on day X belongs to
 * day X), but balances are stateful (AR on day X = invoiced-by-X minus
 * paid-by-X, which an event handler can't compute locally). The rollup
 * approach mirrors the existing as-of-now {@code findSnapshot} logic but
 * persists it per-day so historical balance queries work.
 *
 * <p>Application-side port; JDBC implementation lives in
 * {@code infrastructure/persistence/JdbcFinancialDashboardProjection}.
 */
public interface FinancialDashboardProjection {

    void recordCustomerInvoice(BigDecimal amount, String currencyCode, Instant occurredAt);

    void recordSupplierInvoice(BigDecimal amount, String currencyCode, Instant occurredAt);

    void recordCustomerPayment(BigDecimal amount, String currencyCode, Instant occurredAt);

    void recordSupplierPayment(BigDecimal amount, String currencyCode, Instant occurredAt);

    /**
     * Overwrite the balance columns on the row for {@code (date, currencyCode)}
     * with current SUM-window values computed against reporting's local
     * projections (SO360 for AR + open-SO count; PO tracking for AP + open-PO
     * count; ATP × standard-cost for inventory_value; production_planning_board
     * for open-WO count). Creates the row if it doesn't exist (flow columns
     * default to 0; subsequent flow events upsert them).
     *
     * <p>{@code wip_value} stays at 0 — gated on a costing decision (LIFO /
     * FIFO / weighted-avg) for {@code manufacturing.wip_balance.average_cost}.
     *
     * <p>Currency note: AR / AP / open-SO / open-PO filter on the transaction
     * currency. {@code inventory_value} filters on the product valuation
     * currency. {@code open_work_orders_count} is currency-blind (WOs are
     * physical, not financial). Mismatches don't bite the AUD-only demo
     * dataset but are documented for multi-currency rollouts.
     */
    void refreshDailyBalances(LocalDate date, String currencyCode);
}
