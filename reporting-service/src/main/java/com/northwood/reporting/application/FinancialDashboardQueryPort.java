package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.FinancialDashboardSnapshot;
import com.northwood.reporting.application.dto.FinancialDashboardView;
import com.northwood.shared.domain.Currencies;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinancialDashboardQueryPort {

    /**
     * Default currency for dashboard queries when the caller doesn't specify one.
     * Re-exposed here (off {@link Currencies#BASE_CURRENCY}) so the {@code api/}
     * controller can use it as a {@code @RequestParam} default without importing
     * {@code shared.domain} itself — the application layer is the only seam the
     * controller depends on. A compile-time constant ({@code BASE_CURRENCY} is a
     * constant variable), so it's valid in the annotation.
     */
    String DEFAULT_CURRENCY = Currencies.BASE_CURRENCY;

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
