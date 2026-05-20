package com.northwood.shared.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AssertTest {

    @Nested
    class NotNull {

        @Test void throws_IAE_when_null() {
            IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> Assert.notNull(null, "thing required"));
            assertEquals("thing required", e.getMessage());
        }

        @Test void passes_when_non_null() {
            assertDoesNotThrow(() -> Assert.notNull("x", "thing required"));
        }
    }

    @Nested
    class IsTrue {

        @Test void throws_IAE_when_false() {
            IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> Assert.isTrue(false, "must be true"));
            assertEquals("must be true", e.getMessage());
        }

        @Test void passes_when_true() {
            assertDoesNotThrow(() -> Assert.isTrue(true, "must be true"));
        }
    }

    @Nested
    class IsFalse {

        @Test void throws_IAE_when_true() {
            IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> Assert.isFalse(true, "must be false"));
            assertEquals("must be false", e.getMessage());
        }

        @Test void passes_when_false() {
            assertDoesNotThrow(() -> Assert.isFalse(false, "must be false"));
        }
    }

    @Nested
    class State {

        @Test void throws_ISE_when_false() {
            IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> Assert.state(false, "wrong state"));
            assertEquals("wrong state", e.getMessage());
        }

        @Test void passes_when_true() {
            assertDoesNotThrow(() -> Assert.state(true, "wrong state"));
        }
    }

    @Nested
    class NotBlank {

        @Test void throws_IAE_when_null() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notBlank(null, "needs text"));
        }

        @Test void throws_IAE_when_empty() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notBlank("", "needs text"));
        }

        @Test void throws_IAE_when_whitespace_only() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notBlank("   \t\n", "needs text"));
        }

        @Test void passes_when_text_present() {
            assertDoesNotThrow(() -> Assert.notBlank("x", "needs text"));
        }
    }

    @Nested
    class NotEmpty {

        @Test void collection_throws_when_null() {
            assertThrows(IllegalArgumentException.class,
                () -> Assert.notEmpty((java.util.Collection<?>) null, "needs lines"));
        }

        @Test void collection_throws_when_empty() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notEmpty(List.of(), "needs lines"));
        }

        @Test void collection_passes_when_non_empty() {
            assertDoesNotThrow(() -> Assert.notEmpty(List.of("x"), "needs lines"));
        }

        @Test void map_throws_when_null() {
            assertThrows(IllegalArgumentException.class,
                () -> Assert.notEmpty((Map<?, ?>) null, "needs entries"));
        }

        @Test void map_throws_when_empty() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notEmpty(Map.of(), "needs entries"));
        }

        @Test void map_passes_when_non_empty() {
            assertDoesNotThrow(() -> Assert.notEmpty(Map.of("k", "v"), "needs entries"));
        }
    }

    @Nested
    class UnknownValue {

        @Test void returns_IAE_with_standard_message() {
            IllegalArgumentException e = Assert.unknownValue("status", "frobnicated");
            assertEquals("Unknown status: frobnicated", e.getMessage());
        }

        @Test void handles_null_value_in_message() {
            IllegalArgumentException e = Assert.unknownValue("status", null);
            assertEquals("Unknown status: null", e.getMessage());
        }

        @Test void returns_a_throwable_caller_throws() {
            // Demonstrates the intended call shape: `throw Assert.unknownValue(...);`
            IllegalArgumentException returned = Assert.unknownValue("kind", 42);
            IllegalArgumentException caught = assertThrows(IllegalArgumentException.class, () -> {
                throw returned;
            });
            assertSame(returned, caught);
        }
    }
}
