package com.northwood.finance.application;

import com.northwood.finance.application.dto.RateView;
import com.northwood.finance.domain.CurrencyConverter;
import com.northwood.finance.domain.CurrencyConverter.RateSnapshot;
import com.northwood.shared.application.exception.NotFoundException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Thin application-layer wrapper around {@link CurrencyConverter} so the
 * exchange-rate read endpoint depends on application/ rather than reaching
 * into the domain port directly. The converter port itself stays in
 * {@code domain/} because the {@link com.northwood.finance.domain.SupplierInvoice}
 * aggregate also collaborates with it; domain code can't import application.
 *
 * <p>Translates the domain {@link RateSnapshot} into an application-layer
 * {@link RateView} and wraps {@link CurrencyConverter.RateNotFoundException}
 * as {@link RateNotFoundException} so the controller never imports domain
 * types — see CLAUDE.md "Controllers (api/) must depend only on application/".
 */
@Service
public class ExchangeRateService {

    /**
     * Application-layer wrapper around the domain
     * {@link CurrencyConverter.RateNotFoundException}. Controllers catch this
     * (HTTP 404) instead of importing the domain exception directly.
     */
    public static class RateNotFoundException extends NotFoundException {
        public static final String CODE = "EXCHANGE_RATE_NOT_FOUND";
        public RateNotFoundException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
        @Override public String code() { return CODE; }
        @Override public Map<String, Object> params() {
            // Domain exception's English message carries the missing-rate
            // tuple (from/to/date) — surfaced as 'detail' until the domain
            // exception is promoted to typed accessors.
            return Map.of("detail", getMessage());
        }
    }

    private final CurrencyConverter converter;

    public ExchangeRateService(CurrencyConverter converter) {
        this.converter = converter;
    }

    public RateView rate(String fromCurrency, String toCurrency, LocalDate effectiveAsOf) {
        try {
            RateSnapshot snapshot = converter.rate(fromCurrency, toCurrency, effectiveAsOf);
            return new RateView(snapshot.rate(), snapshot.effectiveDate());
        } catch (CurrencyConverter.RateNotFoundException e) {
            throw new RateNotFoundException(e);
        }
    }
}
