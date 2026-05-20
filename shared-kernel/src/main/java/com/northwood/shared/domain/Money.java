package com.northwood.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Money is a value object: an amount in a given currency. That's the whole
 * identity. Two Money values are equal iff their amount and currency are
 * equal — nothing else.
 *
 * <p>Cross-currency arithmetic (adding USD to AUD) is intentionally rejected.
 * Conversion is a separate concern: it requires a rate, a date, and the
 * direction of conversion, none of which belong on the value itself. When
 * conversion is needed, do it through a {@code CurrencyConverter} service that
 * looks up the rate from {@code finance.exchange_rate} for the relevant date.
 *
 * <p>Where a rate snapshot must be persisted (invoices, payments, journal
 * entries), the snapshot lives on the transaction header alongside its
 * {@code exchange_rate_captured_at} timestamp — not embedded in every Money
 * instance.
 */
public record Money(BigDecimal amount, String currencyCode) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Assert.isTrue(currencyCode.length() == 3, "currencyCode must be 3 letters: " + currencyCode);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currencyCode);
    }

    public static Money zero(String currencyCode) {
        return new Money(BigDecimal.ZERO, currencyCode);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currencyCode);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currencyCode);
    }

    public Money times(BigDecimal multiplier) {
        return new Money(
            amount.multiply(multiplier).setScale(2, RoundingMode.HALF_UP),
            currencyCode
        );
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /**
     * Equality by numeric value + currency, ignoring BigDecimal scale.
     * Use this for no-op suppression on aggregate mutators rather than
     * {@link #equals(Object)}, which is the record default and treats
     * {@code 1.00} as not equal to {@code 1.0}.
     */
    public boolean equalsByValue(Money other) {
        if (other == null) return false;
        return currencyCode.equals(other.currencyCode)
            && amount.compareTo(other.amount) == 0;
    }

    private void requireSameCurrency(Money other) {
        Assert.isTrue(currencyCode.equals(other.currencyCode),
            "Currency mismatch: " + currencyCode + " vs " + other.currencyCode);
    }
}
