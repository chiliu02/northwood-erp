package com.northwood.finance.domain;

import com.northwood.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Converts a {@link Money} amount from its source currency to a target
 * currency at a specific effective date. The rate is looked up in
 * {@code finance.exchange_rate} (latest {@code effective_date <= at}). If
 * source and target currencies match, returns the input unchanged.
 *
 * <p>Phase 5b uses this primarily as a "ready for cross-currency" stub —
 * Northwood transactions are AUD-only today, so the same-currency pass-
 * through is the only path actually exercised. The lookup path is
 * implemented and tested via seeded rates so a future cross-currency
 * showcase doesn't need to build the converter from scratch.
 *
 * <p>Inverse-rate fallback (using 1/rate when only the reverse pair is on
 * file) is supported. Triangulation through a base currency is not.
 */
public interface CurrencyConverter {

    Money convert(Money source, String toCurrency, LocalDate at);

    /** Resolved rate snapshot — what to stamp on a transaction header. */
    record RateSnapshot(BigDecimal rate, LocalDate effectiveDate) {}

    /** Look up just the rate without converting an amount. */
    RateSnapshot rate(String fromCurrency, String toCurrency, LocalDate at);

    class RateNotFoundException extends RuntimeException {
        public RateNotFoundException(String fromCurrency, String toCurrency, LocalDate at) {
            super("No exchange rate on file for " + fromCurrency + "→" + toCurrency
                + " effective on or before " + at);
        }
    }
}
