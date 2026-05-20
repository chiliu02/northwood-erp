package com.northwood.shared.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Quantity is an amount plus its unit of measure. The schema uses NUMERIC(18,4)
 * for all quantity columns; we keep matching scale here.
 *
 * <p>Cross-UoM arithmetic is rejected. Conversion (kg ↔ lb, m ↔ ft) is a domain
 * service concern, not a Quantity concern.
 */
public record Quantity(BigDecimal amount, String uomCode) {

    public Quantity {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(uomCode, "uomCode");
        Assert.isFalse(uomCode.isBlank(), "uomCode must not be blank");
    }

    public static Quantity of(BigDecimal amount, String uomCode) {
        return new Quantity(amount, uomCode);
    }

    public static Quantity zero(String uomCode) {
        return new Quantity(BigDecimal.ZERO, uomCode);
    }

    public Quantity plus(Quantity other) {
        requireSameUom(other);
        return new Quantity(amount.add(other.amount), uomCode);
    }

    public Quantity minus(Quantity other) {
        requireSameUom(other);
        return new Quantity(amount.subtract(other.amount), uomCode);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Quantity other) {
        requireSameUom(other);
        return amount.compareTo(other.amount) > 0;
    }

    private void requireSameUom(Quantity other) {
        Assert.isTrue(uomCode.equals(other.uomCode),
            "UoM mismatch: " + uomCode + " vs " + other.uomCode);
    }
}
