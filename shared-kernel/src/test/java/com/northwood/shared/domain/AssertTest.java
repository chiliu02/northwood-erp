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

        @Test void returns_the_value_for_chaining() {
            // Matches Objects.requireNonNull's signature so that
            // `this.field = Assert.notNull(value, "value")` works.
            String input = "x";
            String returned = Assert.notNull(input, "thing required");
            assertSame(input, returned);
        }
    }

    @Nested
    class NotBlank {

        @Test void throws_IAE_when_null() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notBlank(null, "must not be blank"));
        }

        @Test void throws_IAE_when_empty() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notBlank("", "must not be blank"));
        }

        @Test void throws_IAE_when_whitespace_only() {
            assertThrows(IllegalArgumentException.class, () -> Assert.notBlank("   \t\n", "must not be blank"));
        }

        @Test void passes_when_text_present() {
            assertDoesNotThrow(() -> Assert.notBlank("x", "must not be blank"));
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
    class Argument {

        @Test void throws_IAE_when_false() {
            IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> Assert.argument(false, "must be true"));
            assertEquals("must be true", e.getMessage());
        }

        @Test void passes_when_true() {
            assertDoesNotThrow(() -> Assert.argument(true, "must be true"));
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
    class StateNotNull {

        @Test void throws_ISE_when_null() {
            IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> Assert.stateNotNull(null, "thing required"));
            assertEquals("thing required", e.getMessage());
        }

        @Test void passes_when_non_null() {
            assertDoesNotThrow(() -> Assert.stateNotNull("x", "thing required"));
        }

        @Test void returns_the_value_for_chaining() {
            String input = "x";
            String returned = Assert.stateNotNull(input, "thing required");
            assertSame(input, returned);
        }
    }

    @Nested
    class StateNotBlank {

        @Test void throws_ISE_when_null() {
            assertThrows(IllegalStateException.class, () -> Assert.stateNotBlank(null, "must not be blank"));
        }

        @Test void throws_ISE_when_empty() {
            assertThrows(IllegalStateException.class, () -> Assert.stateNotBlank("", "must not be blank"));
        }

        @Test void throws_ISE_when_whitespace_only() {
            assertThrows(IllegalStateException.class, () -> Assert.stateNotBlank("   \t\n", "must not be blank"));
        }

        @Test void passes_when_text_present() {
            assertDoesNotThrow(() -> Assert.stateNotBlank("x", "must not be blank"));
        }
    }

    @Nested
    class StateNotEmpty {

        @Test void collection_throws_when_null() {
            assertThrows(IllegalStateException.class,
                () -> Assert.stateNotEmpty((java.util.Collection<?>) null, "needs lines"));
        }

        @Test void collection_throws_when_empty() {
            assertThrows(IllegalStateException.class, () -> Assert.stateNotEmpty(List.of(), "needs lines"));
        }

        @Test void collection_passes_when_non_empty() {
            assertDoesNotThrow(() -> Assert.stateNotEmpty(List.of("x"), "needs lines"));
        }

        @Test void map_throws_when_null() {
            assertThrows(IllegalStateException.class,
                () -> Assert.stateNotEmpty((Map<?, ?>) null, "needs entries"));
        }

        @Test void map_throws_when_empty() {
            assertThrows(IllegalStateException.class, () -> Assert.stateNotEmpty(Map.of(), "needs entries"));
        }

        @Test void map_passes_when_non_empty() {
            assertDoesNotThrow(() -> Assert.stateNotEmpty(Map.of("k", "v"), "needs entries"));
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
            IllegalArgumentException returned = Assert.unknownValue("kind", 42);
            IllegalArgumentException caught = assertThrows(IllegalArgumentException.class, () -> {
                throw returned;
            });
            assertSame(returned, caught);
        }
    }
}
