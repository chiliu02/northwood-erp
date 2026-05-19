package com.northwood.shared.domain;

/**
 * Project-wide convention for assigning sequence numbers to lines on a
 * master-detail document: start at {@link #START}, increment by {@link #STEP}.
 * The gap leaves room for manual line insertions (e.g. 5 between 0 and 10, 15
 * between 10 and 20) without renumbering subsequent lines.
 *
 * <p>Applied by every aggregate that builds line collections at creation
 * time — sales orders, purchase requisitions, supplier invoices, stock
 * reservations, journal entries, etc. The values match what these aggregates
 * persist into their {@code *_line.line_number} columns.
 *
 * <p>Pure formatting choice — no consumer dispatches on the value. Surfacing
 * it through a constant pins the convention in one place so a future "renumber
 * with a different scheme" decision is a single rename surface.
 */
public final class LineNumbering {

    /** First line's number. */
    public static final int START = 10;

    /** Increment between adjacent lines. */
    public static final int STEP = 10;

    private LineNumbering() {}
}
