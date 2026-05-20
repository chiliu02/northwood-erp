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
 * <p><b>Shape conventions.</b>
 * <ul>
 *   <li>{@link #notNull}, {@link #isTrue}, {@link #notBlank}, {@link #notEmpty}
 *       throw {@link IllegalArgumentException} — caller passed in something
 *       that violates the contract.</li>
 *   <li>{@link #state} throws {@link IllegalStateException} — the receiver's
 *       current state doesn't permit the operation (e.g. mutating a posted
 *       invoice, advancing a saga from a non-startable state).</li>
 *   <li>{@link #unknownValue} returns the exception rather than throwing,
 *       so the caller writes {@code throw Assert.unknownValue(...)} —
 *       lets the compiler keep its control-flow analysis on the throw
 *       statement and matches the shape of the existing end-of-method
 *       fall-through throws in {@code fromDb} / {@code fromString} parsers.</li>
 * </ul>
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

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code obj} is null. Use for non-null argument checks at the top of
     * factory methods, application service entry points, and constructor
     * bodies.
     */
    public static void notNull(Object obj, String message) {
        if (obj == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code condition} is false. The condition is the <b>positive</b>
     * statement of what should be true (e.g. {@code isTrue(qty > 0, ...)},
     * not {@code isTrue(qty <= 0, ...)}). For checks shaped as "fail if
     * this forbidden condition holds" use {@link #isFalse} instead —
     * keeps the original phrasing without forcing the caller to invert.
     */
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code condition} is true — the forbidden-condition mirror of
     * {@link #isTrue}. Use this when the original code shape is
     * {@code if (cond) throw new IAE(...)} and inverting the condition
     * would produce a double-negative (e.g. {@code isFalse(uomCode.isBlank(),
     * "uomCode must not be blank")} reads better than {@code isTrue(
     * !uomCode.isBlank(), ...)}).
     */
    public static void isFalse(boolean condition, String message) {
        if (condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Throws {@link IllegalStateException} with {@code message} when
     * {@code condition} is false. Same shape as {@link #isTrue} but for
     * receiver-state invariants rather than argument contracts.
     */
    public static void state(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code text} is null, empty, or whitespace-only. Matches the
     * behaviour of {@link String#isBlank()}.
     */
    public static void notBlank(String text, String message) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Throws {@link IllegalArgumentException} with {@code message} when
     * {@code collection} is null or empty. Use for "at least one ..."
     * argument contracts (e.g. multi-line factories that need at least
     * one line).
     */
    public static void notEmpty(Collection<?> collection, String message) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Same as {@link #notEmpty(Collection, String)} for maps. */
    public static void notEmpty(Map<?, ?> map, String message) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Returns (does not throw) an {@link IllegalArgumentException} carrying
     * the standard {@code "Unknown <field>: <value>"} message. Designed to
     * be used as {@code throw Assert.unknownValue("status", value);} at the
     * fall-through end of an enum-parser ({@code fromDb} / {@code fromString})
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
