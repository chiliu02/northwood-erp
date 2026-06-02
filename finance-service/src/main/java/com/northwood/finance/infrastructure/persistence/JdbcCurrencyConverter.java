package com.northwood.finance.infrastructure.persistence;

import com.northwood.finance.domain.CurrencyConverter;
import com.northwood.shared.domain.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCurrencyConverter implements CurrencyConverter {

    private final JdbcTemplate jdbc;

    public JdbcCurrencyConverter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Money convert(Money source, String toCurrency, LocalDate at) {
        if (source.currencyCode().equalsIgnoreCase(toCurrency)) {
            return source;
        }
        RateSnapshot snap = rate(source.currencyCode(), toCurrency, at);
        BigDecimal converted = source.amount()
            .multiply(snap.rate())
            .setScale(2, RoundingMode.HALF_UP);
        return Money.of(converted, toCurrency);
    }

    @Override
    public RateSnapshot rate(String fromCurrency, String toCurrency, LocalDate at) {
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return new RateSnapshot(BigDecimal.ONE, at);
        }

        // Direct rate first.
        List<RateSnapshot> direct = jdbc.query("""
            SELECT rate, effective_date
            FROM finance.exchange_rate
            WHERE from_currency = ? AND to_currency = ? AND effective_date <= ?
            ORDER BY effective_date DESC
            LIMIT 1
            """,
            (rs, n) -> new RateSnapshot(rs.getBigDecimal("rate"), rs.getDate("effective_date").toLocalDate()),
            fromCurrency, toCurrency, Date.valueOf(at)
        );
        if (!direct.isEmpty()) {
            return direct.get(0);
        }

        // Inverse rate fallback: if AUD→USD is missing but USD→AUD is on file,
        // use 1/rate.
        List<RateSnapshot> inverse = jdbc.query("""
            SELECT rate, effective_date
            FROM finance.exchange_rate
            WHERE from_currency = ? AND to_currency = ? AND effective_date <= ?
            ORDER BY effective_date DESC
            LIMIT 1
            """,
            (rs, n) -> new RateSnapshot(rs.getBigDecimal("rate"), rs.getDate("effective_date").toLocalDate()),
            toCurrency, fromCurrency, Date.valueOf(at)
        );
        if (!inverse.isEmpty()) {
            RateSnapshot inv = inverse.get(0);
            BigDecimal flipped = BigDecimal.ONE.divide(inv.rate(), 8, RoundingMode.HALF_UP);
            return new RateSnapshot(flipped, inv.effectiveDate());
        }

        throw new RateNotFoundException(fromCurrency, toCurrency, at);
    }
}
