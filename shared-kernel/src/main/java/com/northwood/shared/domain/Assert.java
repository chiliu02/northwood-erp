package com.northwood.shared.domain;

import java.util.Collection;
import java.util.Map;

/**
 * Project-wide precondition and invariant helpers. Replaces the
 * {@code if (x == null) throw new IllegalArgumentException(...)} boilerplate
 * with single-call equivalents that read closer to "what should be true"
 * rather than "what's wrong."
 *
 * <p>Lives in {@code shared-kernel} so every layer — including pure domain
 * code under the hexagonal rules — can call it. Spring's
 * {@code org.springframework.util.Assert} would have been the obvious
 * shape but pulling Spring into {@code shared-kernel} breaks the explicit
 * framework-free guarantee its {@code pom.xml} carries. This is a tiny,
 * zero-dependency equivalent shaped to the same idioms.
 *
 * <p><b>Two axes: argument vs. state.</b>
 * <ul>
 *   <li><b>Argument checks</b> throw {@link IllegalArgumentException} — the
 *       caller passed in something that violates the contract.
 *       {@link #notNull}, {@link #notBlank}, {@link #notEmpty},
 *       {@link #argument}.</li>
 *   <li><b>State checks</b> throw {@link IllegalStateException} — the
 *       receiver's current state doesn't permit the operation (e.g.
 *       mutating a posted invoice, advancing a saga from a non-startable
 *       state). {@link #state}, {@link #stateNotNull},
 *       {@link #stateNotBlank}, {@link #stateNotEmpty}.</li>
 * </ul>
 *
 * <p>The {@code state*} family mirrors the argument family one-for-one so a
 * caller never has to spell out the negated boolean by hand:
 * {@code Assert.stateNotEmpty(list, ...)} reads cleanly where
 * {@code Assert.state(!list.isEmpty(), ...)} would carry a redundant {@code !}.
 *
 * <p>{@link #unknownValue} returns the exception rather than throwing, so the
 * caller writes {@code throw Assert.unknownValue(...)} — lets the compiler
 * keep the {@code throw} keyword visible for control-flow analysis
 * (unreachable-code, definite-assignment) and matches the shape of the
 * existing end-of-method fall-through throws in {@code fromCode} /
 * {@code fromString} parsers.
 *
 * <p><b>Not covered.</b>
 * <ul>
 *   <li>Exception translation ({@code catch (X) { throw new Y(...) }}) —
 *       that's wrapping, not asserting; leave as inline throws.</li>
 *   <li>Switch {@code default} clauses where the message isn't
 *       {@code "Unknown <field>: <value>"} — keep as inline throws when
 *       the message carries different context.</li>
 * </ul>
 */
public final class Assert {

    // ----------------------------------------------------------------------
    // Argument checks — throw IllegalArgumentException
    // ----------------------------------------------------------------------

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code obj} is null; otherwise returns {@code obj} unchanged. Use for
     * non-null argument checks at the top of factory methods, application
     * service entry points, and constructor bodies — including the chained
     * shape {@code this.field = Assert.notNull(value, "value")} that
     * mirrors {@link java.util.Objects#requireNonNull(Object, String)}.
     */
    public static <T> T notNull(T obj, String message) {
        if (obj == null) {
            throw new IllegalArgumentException(message);
        }
        return obj;
    }

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code text} is null, empty, or whitespace-only; otherwise returns
     * {@code text} unchanged. Matches the behaviour of {@link String#isBlank()}.
     * Supports the chained {@code this.field = Assert.notBlank(value, "value")}
     * shape, same as {@link #notNull}.
     */
    public static String notBlank(String text, String message) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text;
    }

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code collection} is null or empty; otherwise returns {@code collection}
     * unchanged. Use for "at least one ..." argument contracts (e.g. multi-line
     * factories that need at least one line). The concrete collection type is
     * preserved so {@code this.lines = Assert.notEmpty(lines, "lines")} chains.
     */
    public static <C extends Collection<?>> C notEmpty(C collection, String message) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return collection;
    }

    /** Same as {@link #notEmpty(Collection, String)} for maps. */
    public static <M extends Map<?, ?>> M notEmpty(M map, String message) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return map;
    }

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code condition} is false. The condition is the <b>positive</b>
     * statement of what should be true (e.g. {@code argument(qty > 0, ...)},
     * not {@code argument(!(qty <= 0), ...)}). Use this for argument-shape
     * predicates that don't fit the more-specific {@link #notNull} /
     * {@link #notBlank} / {@link #notEmpty} helpers.
     */
    public static void argument(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    // ----------------------------------------------------------------------
    // State checks — throw IllegalStateException
    // ----------------------------------------------------------------------

    /**
     * Throws {@link IllegalStateException} with {@code message} when
     * {@code condition} is false. Same shape as {@link #argument} but for
     * receiver-state invariants rather than argument contracts.
     */
    public static void state(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * State-check mirror of {@link #notNull}: throws
     * {@link IllegalStateException} when {@code obj} is null; otherwise
     * returns {@code obj} for chaining. Use when the missing reference
     * signals an invariant violation rather than a bad argument (e.g. a
     * saga in a state that should have populated {@code workOrderId}).
     */
    public static <T> T stateNotNull(T obj, String message) {
        if (obj == null) {
            throw new IllegalStateException(message);
        }
        return obj;
    }

    /**
     * State-check mirror of {@link #notBlank}: throws
     * {@link IllegalStateException} when {@code text} is null, empty, or
     * whitespace-only; otherwise returns {@code text} for chaining.
     */
    public static String stateNotBlank(String text, String message) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(message);
        }
        return text;
    }

    /**
     * State-check mirror of {@link #notEmpty(Collection, String)}: throws
     * {@link IllegalStateException} when {@code collection} is null or empty;
     * otherwise returns {@code collection} for chaining.
     */
    public static <C extends Collection<?>> C stateNotEmpty(C collection, String message) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalStateException(message);
        }
        return collection;
    }

    /** Same as {@link #stateNotEmpty(Collection, String)} for maps. */
    public static <M extends Map<?, ?>> M stateNotEmpty(M map, String message) {
        if (map == null || map.isEmpty()) {
            throw new IllegalStateException(message);
        }
        return map;
    }

    // ----------------------------------------------------------------------
    // Unknown enum / wire value fall-through
    // ----------------------------------------------------------------------

    /**
     * Returns (does not throw) an {@link IllegalArgumentException} carrying
     * the standard {@code "Unknown <field>: <value>"} message. Designed to
     * be used as {@code throw Assert.unknownValue("status", value);} at the
     * fall-through end of an enum-parser ({@code fromCode} / {@code fromString})
     * or a switch {@code default} clause.
     *
     * <p>Returning rather than throwing lets the compiler keep the {@code
     * throw} keyword visible at the call site — important for control-flow
     * analysis (e.g. unreachable-code checks) and reader intent.
     */
    public static IllegalArgumentException unknownValue(String field, Object value) {
        return new IllegalArgumentException("Unknown " + field + ": " + value);
    }

    private Assert() {
        // Utility class; not instantiable.
    }
}
