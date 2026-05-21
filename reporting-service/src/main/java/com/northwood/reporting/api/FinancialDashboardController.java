package com.northwood.reporting.api;

import com.northwood.reporting.application.dto.FinancialDashboardSnapshot;
import com.northwood.reporting.application.dto.FinancialDashboardView;
import com.northwood.reporting.application.FinancialDashboardQueryPort;
import com.northwood.shared.domain.Currencies;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/financial-dashboard")
public class FinancialDashboardController {

    private final FinancialDashboardQueryPort port;

    public FinancialDashboardController(FinancialDashboardQueryPort port) {
        this.port = port;
    }

    /** All days for the currency (newest first). */
    @GetMapping
    public List<FinancialDashboardView> list(
        @RequestParam(name = "currency", defaultValue = Currencies.BASE_CURRENCY) String currencyCode
    ) {
        return port.findByCurrency(currencyCode);
    }

    /**
     * As-of-now snapshot — AR / AP / currently-open counts via SUM-window
     * over reporting's local projections. Distinct from {@link #list} which
     * returns per-day delta rows; this is the running total right now.
     */
    @GetMapping("/snapshot")
    public FinancialDashboardSnapshot snapshot(
        @RequestParam(name = "currency", defaultValue = Currencies.BASE_CURRENCY) String currencyCode
    ) {
        return port.findSnapshot(currencyCode);
    }

    @GetMapping("/{date}")
    public ResponseEntity<FinancialDashboardView> get(
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(name = "currency", defaultValue = Currencies.BASE_CURRENCY) String currencyCode
    ) {
        return port.findByDate(date, currencyCode)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
