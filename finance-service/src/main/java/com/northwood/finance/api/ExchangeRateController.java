package com.northwood.finance.api;

import com.northwood.finance.application.ExchangeRateService;
import com.northwood.finance.application.ExchangeRateService.RateNotFoundException;
import com.northwood.finance.application.dto.RateView;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §3.8: ad-hoc exchange-rate lookup. {@code GET /api/exchange-rate?from=USD&to=AUD&date=2026-05-06}
 * resolves the latest {@code effective_date <= date} pair (with inverse-rate
 * fallback when only the reverse pair is on file). Same-currency requests
 * return rate {@code 1.0} with {@code effectiveDate = date}.
 *
 * <p>404 when no rate is on file for the pair on or before the given date.
 */
@RestController
@RequestMapping("/api/exchange-rate")
public class ExchangeRateController {

    private final ExchangeRateService service;

    public ExchangeRateController(ExchangeRateService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ExchangeRateResponse> lookup(
        @RequestParam("from") String fromCurrency,
        @RequestParam("to") String toCurrency,
        @RequestParam(value = "date", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        LocalDate effectiveAsOf = date == null ? LocalDate.now() : date;
        RateView view = service.rate(fromCurrency, toCurrency, effectiveAsOf);
        return ResponseEntity.ok(new ExchangeRateResponse(
            fromCurrency,
            toCurrency,
            view.rate(),
            view.effectiveDate()
        ));
    }

    @ExceptionHandler(RateNotFoundException.class)
    public ResponseEntity<String> handleNotFound(RateNotFoundException e) {
        return ResponseEntity.status(404).body(e.getMessage());
    }

    public record ExchangeRateResponse(
        String fromCurrency,
        String toCurrency,
        BigDecimal rate,
        LocalDate effectiveDate
    ) {}
}
