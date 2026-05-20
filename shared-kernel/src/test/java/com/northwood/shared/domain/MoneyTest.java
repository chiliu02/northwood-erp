package com.northwood.shared.domain;

import com.northwood.shared.domain.Currencies;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Nested
    class Construction {
        @Test void rejects_null_amount() {
            assertThrows(IllegalArgumentException.class, () -> new Money(null, Currencies.AUD));
        }

        @Test void rejects_null_currency() {
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.TEN, null));
        }

        @Test void rejects_currency_other_than_three_letters() {
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.TEN, "USDX"));
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.TEN, "EU"));
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.TEN, ""));
        }

        @Test void zero_factory_yields_zero_in_currency() {
            Money m = Money.zero(Currencies.AUD);
            assertEquals(BigDecimal.ZERO, m.amount());
            assertEquals(Currencies.AUD, m.currencyCode());
            assertTrue(m.isZero());
        }
    }

    @Nested
    class Equality {
        @Test void equal_when_amount_and_currency_match() {
            assertEquals(Money.of(new BigDecimal("12.50"), Currencies.AUD),
                         Money.of(new BigDecimal("12.50"), Currencies.AUD));
        }

        @Test void not_equal_when_currency_differs() {
            assertNotEquals(Money.of(BigDecimal.TEN, Currencies.AUD),
                            Money.of(BigDecimal.TEN, Currencies.USD));
        }

        @Test void not_equal_when_amount_differs() {
            assertNotEquals(Money.of(BigDecimal.TEN, Currencies.AUD),
                            Money.of(BigDecimal.ONE, Currencies.AUD));
        }

        @Test void equals_is_scale_sensitive() {
            // Record-default equals uses BigDecimal.equals which considers scale.
            // Caller must use equalsByValue(...) for numeric-only comparison.
            assertNotEquals(Money.of(new BigDecimal("100.00"), Currencies.AUD),
                            Money.of(new BigDecimal("100.0"), Currencies.AUD));
        }
    }

    @Nested
    class EqualsByValue {
        @Test void true_when_amount_and_currency_match() {
            assertTrue(Money.of(new BigDecimal("100.00"), Currencies.AUD)
                .equalsByValue(Money.of(new BigDecimal("100.00"), Currencies.AUD)));
        }

        @Test void true_ignoring_BigDecimal_scale() {
            assertTrue(Money.of(new BigDecimal("100.00"), Currencies.AUD)
                .equalsByValue(Money.of(new BigDecimal("100.0"), Currencies.AUD)));
            assertTrue(Money.of(new BigDecimal("100"), Currencies.AUD)
                .equalsByValue(Money.of(new BigDecimal("100.000"), Currencies.AUD)));
        }

        @Test void false_when_amount_differs() {
            assertFalse(Money.of(new BigDecimal("100.00"), Currencies.AUD)
                .equalsByValue(Money.of(new BigDecimal("100.01"), Currencies.AUD)));
        }

        @Test void false_when_currency_differs() {
            assertFalse(Money.of(new BigDecimal("100.00"), Currencies.AUD)
                .equalsByValue(Money.of(new BigDecimal("100.00"), Currencies.USD)));
        }

        @Test void false_when_other_is_null() {
            assertFalse(Money.zero(Currencies.AUD).equalsByValue(null));
        }
    }

    @Nested
    class Arithmetic {
        @Test void plus_sums_amounts_in_same_currency() {
            Money result = Money.of(new BigDecimal("10.00"), Currencies.AUD)
                .plus(Money.of(new BigDecimal("2.50"), Currencies.AUD));
            assertEquals(new BigDecimal("12.50"), result.amount());
            assertEquals(Currencies.AUD, result.currencyCode());
        }

        @Test void minus_subtracts_amounts_in_same_currency() {
            Money result = Money.of(new BigDecimal("10.00"), Currencies.AUD)
                .minus(Money.of(new BigDecimal("2.50"), Currencies.AUD));
            assertEquals(new BigDecimal("7.50"), result.amount());
        }

        @Test void plus_rejects_cross_currency() {
            assertThrows(IllegalArgumentException.class, () ->
                Money.of(BigDecimal.TEN, Currencies.AUD).plus(Money.of(BigDecimal.TEN, Currencies.USD)));
        }

        @Test void minus_rejects_cross_currency() {
            assertThrows(IllegalArgumentException.class, () ->
                Money.of(BigDecimal.TEN, Currencies.AUD).minus(Money.of(BigDecimal.ONE, Currencies.USD)));
        }

        @Test void times_scales_amount_to_two_dp_half_up() {
            Money result = Money.of(new BigDecimal("10.005"), Currencies.AUD).times(new BigDecimal("3"));
            assertEquals(new BigDecimal("30.02"), result.amount());
        }

        @Test void times_preserves_currency() {
            assertEquals(Currencies.AUD,
                Money.of(BigDecimal.ONE, Currencies.AUD).times(BigDecimal.TEN).currencyCode());
        }
    }

    @Nested
    class Predicates {
        @Test void isZero_true_for_zero() {
            assertTrue(Money.zero(Currencies.AUD).isZero());
            assertTrue(Money.of(new BigDecimal("0.00"), Currencies.AUD).isZero());
        }

        @Test void isZero_false_for_nonzero() {
            assertFalse(Money.of(BigDecimal.ONE, Currencies.AUD).isZero());
            assertFalse(Money.of(new BigDecimal("-1"), Currencies.AUD).isZero());
        }

        @Test void isPositive_only_for_strictly_positive() {
            assertTrue(Money.of(new BigDecimal("0.01"), Currencies.AUD).isPositive());
            assertFalse(Money.zero(Currencies.AUD).isPositive());
            assertFalse(Money.of(new BigDecimal("-1"), Currencies.AUD).isPositive());
        }
    }
}
