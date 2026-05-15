package com.northwood.reporting.infrastructure.dashboard;

import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic rollup that keeps the balance columns on
 * {@code reporting.financial_dashboard_daily} current. Refreshes today's row
 * by computing SUM-window values over reporting's local projections (SO360,
 * PO tracking, ATP × standard-cost, production_planning_board) and
 * overwriting AR / AP / inventory_value / open-SO / open-PO / open-WO counts.
 *
 * <p>Flow columns (revenue / COGS / gross / cash in / cash out) are not
 * touched here — those are owned by the event-driven {@code record*} methods
 * on {@link FinancialDashboardProjection} which increment per arriving event.
 *
 * <p>Cadence: {@code northwood.reporting.dashboard.balanceRefreshIntervalMs}
 * (default 60_000 = 1 minute). Demo-friendly: any user action that emits an
 * AR/AP/inventory-affecting event will see the rollup within a minute.
 *
 * <p>Real-time freshness is still served by
 * {@code JdbcFinancialDashboardQueryPort.findSnapshot} (the {@code /snapshot}
 * endpoint), which queries the same SUM-window logic synchronously on every
 * request. The worker's job is to persist that into per-day rows so
 * historical balance queries ({@code GET /api/financial-dashboard/{date}})
 * return meaningful numbers.
 */
@Component
public class FinancialDashboardBalanceWorker {

    private static final Logger log = LoggerFactory.getLogger(FinancialDashboardBalanceWorker.class);

    /**
     * Currencies to refresh. Demo dataset is AUD-only; production deployments
     * should override via {@code northwood.reporting.dashboard.currencies}.
     */
    private final String[] currencies;
    private final FinancialDashboardProjection projection;

    public FinancialDashboardBalanceWorker(
        FinancialDashboardProjection projection,
        @Value("${northwood.reporting.dashboard.currencies:AUD}") String currenciesCsv
    ) {
        this.projection = projection;
        this.currencies = currenciesCsv.split(",");
    }

    @Scheduled(fixedRateString = "${northwood.reporting.dashboard.balanceRefreshIntervalMs:60000}")
    public void refresh() {
        LocalDate today = LocalDate.now();
        for (String raw : currencies) {
            String currency = raw.trim();
            if (currency.isEmpty()) continue;
            try {
                projection.refreshDailyBalances(today, currency);
            } catch (RuntimeException e) {
                log.warn("dashboard balance refresh failed for date={} currency={}: {}",
                    today, currency, e.getMessage());
            }
        }
        log.debug("dashboard balance rollup refreshed: date={} currencies={}", today, currencies.length);
    }
}
