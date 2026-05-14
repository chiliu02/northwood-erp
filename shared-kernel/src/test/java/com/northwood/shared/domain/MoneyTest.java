package com.northwood.shared.domain;

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
            assertThrows(NullPointerException.class, () -> new Money(null, "AUD"));
        }

        @Test void rejects_null_currency() {
            assertThrows(NullPointerException.class, () -> new Money(BigDecimal.TEN, null));
        }

        @Test void rejects_currency_other_than_three_letters() {
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.TEN, "USDX"));
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.TEN, "EU"));
            assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.TEN, ""));
        }

        @Test void zero_factory_yields_zero_in_currency() {
            Money m = Money.zero("AUD");
            assertEquals(BigDecimal.ZERO, m.amount());
            assertEquals("AUD", m.currencyCode());
            assertTrue(m.isZero());
        }
    }

    @Nested
    class Equality {
        @Test void equal_when_amount_and_currency_match() {
            assertEquals(Money.of(new BigDecimal("12.50"), "AUD"),
                         Money.of(new BigDecimal("12.50"), "AUD"));
        }

        @Test void not_equal_when_currency_differs() {
            assertNotEquals(Money.of(BigDecimal.TEN, "AUD"),
                            Money.of(BigDecimal.TEN, "USD"));
        }

        @Test void not_equal_when_amount_differs() {
            assertNotEquals(Money.of(BigDecimal.TEN, "AUD"),
                            Money.of(BigDecimal.ONE, "AUD"));
        }

        @Test void equals_is_scale_sensitive() {
            // Record-default equals uses BigDecimal.equals which considers scale.
            // Caller must use equalsByValue(...) for numeric-only comparison.
            assertNotEquals(Money.of(new BigDecimal("100.00"), "AUD"),
                            Money.of(new BigDecimal("100.0"), "AUD"));
        }
    }

    @Nested
    class EqualsByValue {
        @Test void true_when_amount_and_currency_match() {
            assertTrue(Money.of(new BigDecimal("100.00"), "AUD")
                .equalsByValue(Money.of(new BigDecimal("100.00"), "AUD")));
        }

        @Test void true_ignoring_BigDecimal_scale() {
            assertTrue(Money.of(new BigDecimal("100.00"), "AUD")
                .equalsByValue(Money.of(new BigDecimal("100.0"), "AUD")));
            assertTrue(Money.of(new BigDecimal("100"), "AUD")
                .equalsByValue(Money.of(new BigDecimal("100.000"), "AUD")));
        }

        @Test void false_when_amount_differs() {
            assertFalse(Money.of(new BigDecimal("100.00"), "AUD")
                .equalsByValue(Money.of(new BigDecimal("100.01"), "AUD")));
        }

        @Test void false_when_currency_differs() {
            assertFalse(Money.of(new BigDecimal("100.00"), "AUD")
                .equalsByValue(Money.of(new BigDecimal("100.00"), "USD")));
        }

        @Test void false_when_other_is_null() {
            assertFalse(Money.zero("AUD").equalsByValue(null));
        }
    }

    @Nested
    class Arithmetic {
        @Test void plus_sums_amounts_in_same_currency() {
            Money result = Money.of(new BigDecimal("10.00"), "AUD")
                .plus(Money.of(new BigDecimal("2.50"), "AUD"));
            assertEquals(new BigDecimal("12.50"), result.amount());
            assertEquals("AUD", result.currencyCode());
        }

        @Test void minus_subtracts_amounts_in_same_currency() {
            Money result = Money.of(new BigDecimal("10.00"), "AUD")
                .minus(Money.of(new BigDecimal("2.50"), "AUD"));
            assertEquals(new BigDecimal("7.50"), result.amount());
        }

        @Test void plus_rejects_cross_currency() {
            assertThrows(IllegalArgumentException.class, () ->
                Money.of(BigDecimal.TEN, "AUD").plus(Money.of(BigDecimal.TEN, "USD")));
        }

        @Test void minus_rejects_cross_currency() {
            assertThrows(IllegalArgumentException.class, () ->
                Money.of(BigDecimal.TEN, "AUD").minus(Money.of(BigDecimal.ONE, "USD")));
        }

        @Test void times_scales_amount_to_two_dp_half_up() {
            Money result = Money.of(new BigDecimal("10.005"), "AUD").times(new BigDecimal("3"));
            assertEquals(new BigDecimal("30.02"), result.amount());
        }

        @Test void times_preserves_currency() {
            assertEquals("AUD",
                Money.of(BigDecimal.ONE, "AUD").times(BigDecimal.TEN).currencyCode());
        }
    }

    @Nested
    class Predicates {
        @Test void isZero_true_for_zero() {
            assertTrue(Money.zero("AUD").isZero());
            assertTrue(Money.of(new BigDecimal("0.00"), "AUD").isZero());
        }

        @Test void isZero_false_for_nonzero() {
            assertFalse(Money.of(BigDecimal.ONE, "AUD").isZero());
            assertFalse(Money.of(new BigDecimal("-1"), "AUD").isZero());
        }

        @Test void isPositive_only_for_strictly_positive() {
            assertTrue(Money.of(new BigDecimal("0.01"), "AUD").isPositive());
            assertFalse(Money.zero("AUD").isPositive());
            assertFalse(Money.of(new BigDecimal("-1"), "AUD").isPositive());
        }
    }
}
