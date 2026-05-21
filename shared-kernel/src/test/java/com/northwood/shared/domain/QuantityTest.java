package com.northwood.shared.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class QuantityTest {

    @Nested
    class Construction {
        @Test void rejects_null_amount() {
            assertThrows(IllegalArgumentException.class, () -> new Quantity(null, "EA"));
        }

        @Test void rejects_null_uom() {
            assertThrows(IllegalArgumentException.class, () -> new Quantity(BigDecimal.ONE, null));
        }

        @Test void rejects_blank_uom() {
            assertThrows(IllegalArgumentException.class, () -> new Quantity(BigDecimal.ONE, ""));
            assertThrows(IllegalArgumentException.class, () -> new Quantity(BigDecimal.ONE, "  "));
        }
    }

    @Nested
    class Arithmetic {
        @Test void plus_sums_when_uom_matches() {
            Quantity result = Quantity.of(new BigDecimal("3"), "EA")
                .plus(Quantity.of(new BigDecimal("2"), "EA"));
            assertEquals(new BigDecimal("5"), result.amount());
            assertEquals("EA", result.uomCode());
        }

        @Test void minus_subtracts_when_uom_matches() {
            Quantity result = Quantity.of(new BigDecimal("5"), "EA")
                .minus(Quantity.of(new BigDecimal("2"), "EA"));
            assertEquals(new BigDecimal("3"), result.amount());
        }

        @Test void plus_rejects_cross_uom() {
            assertThrows(IllegalArgumentException.class, () ->
                Quantity.of(BigDecimal.ONE, "EA").plus(Quantity.of(BigDecimal.ONE, "KG")));
        }

        @Test void minus_rejects_cross_uom() {
            assertThrows(IllegalArgumentException.class, () ->
                Quantity.of(BigDecimal.TEN, "EA").minus(Quantity.of(BigDecimal.ONE, "KG")));
        }
    }

    @Nested
    class Comparison {
        @Test void isGreaterThan_when_amount_strictly_larger() {
            assertTrue(Quantity.of(new BigDecimal("5"), "EA")
                .isGreaterThan(Quantity.of(new BigDecimal("3"), "EA")));
        }

        @Test void isGreaterThan_false_when_equal() {
            assertFalse(Quantity.of(new BigDecimal("3"), "EA")
                .isGreaterThan(Quantity.of(new BigDecimal("3"), "EA")));
        }

        @Test void isGreaterThan_false_when_smaller() {
            assertFalse(Quantity.of(new BigDecimal("1"), "EA")
                .isGreaterThan(Quantity.of(new BigDecimal("3"), "EA")));
        }

        @Test void isGreaterThan_rejects_cross_uom() {
            assertThrows(IllegalArgumentException.class, () ->
                Quantity.of(BigDecimal.TEN, "EA").isGreaterThan(Quantity.of(BigDecimal.ONE, "KG")));
        }
    }

    @Nested
    class Predicates {
        @Test void isZero_true_for_zero() {
            assertTrue(Quantity.zero("EA").isZero());
            assertTrue(Quantity.of(new BigDecimal("0.0000"), "EA").isZero());
        }

        @Test void isPositive_only_strictly_positive() {
            assertTrue(Quantity.of(new BigDecimal("0.0001"), "EA").isPositive());
            assertFalse(Quantity.zero("EA").isPositive());
            assertFalse(Quantity.of(new BigDecimal("-1"), "EA").isPositive());
        }
    }
}
