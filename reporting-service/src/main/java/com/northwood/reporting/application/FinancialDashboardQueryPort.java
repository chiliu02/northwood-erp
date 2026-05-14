package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.FinancialDashboardSnapshot;
import com.northwood.reporting.application.dto.FinancialDashboardView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinancialDashboardQueryPort {

    /** All rows for the given currency, ordered descending by date (newest first). */
    List<FinancialDashboardView> findByCurrency(String currencyCode);

    /** Single day's row, if it exists. */
    Optional<FinancialDashboardView> findByDate(LocalDate date, String currencyCode);

    /**
     * As-of-now totals (AR, AP, currently-open counts) computed via SUM-window
     * over reporting's local projections. Always returns a record — zeros when
     * no data has been projected yet.
     */
    FinancialDashboardSnapshot findSnapshot(String currencyCode);
}
