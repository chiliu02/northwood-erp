package com.northwood.testharness.inmemory;

import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory outbox backing one service's "outbox table". Pending rows
 * stack up in insertion (sequence_number) order; {@link SynchronousBus}
 * drains them.
 *
 * <p>Each service's test-kit owns one of these — the per-schema isolation
 * pattern from production is preserved by giving each kit its own instance.
 */
public final class InMemoryOutboxPort implements OutboxPort {

    private final List<OutboxRow> rows = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(0);

    @Override
    public synchronized List<OutboxRow> findPending(int limit) {
        List<OutboxRow> out = new ArrayList<>();
        for (OutboxRow r : rows) {
            // Mirror JdbcOutboxAdapter's WHERE status IN ('pending', 'failed') —
            // failed rows are re-surfaced for retry, not dropped.
            if (OutboxRow.PENDING.equals(r.getStatus()) || OutboxRow.FAILED.equals(r.getStatus())) {
                out.add(r);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    @Override
    public synchronized void update(OutboxRow row) {
        // Treat as in-place update — the row reference came from findPending
        // and the drainer mutated it via markPublished/markFailed.
    }

    @Override
    public synchronized void appendPending(OutboxRow row) {
        // Mimic Jdbc adapter: stamp sequence_number on insert via reflection-free
        // proxy — OutboxRow has no sequence setter, so we record the order via
        // list position; findPending already returns in insertion order.
        rows.add(row);
        sequence.incrementAndGet();
    }

    /** Total rows ever appended (pending or published). Test-side only. */
    public synchronized int size() { return rows.size(); }

    /** Read all rows (any status). Test-side only — for assertions. */
    public synchronized List<OutboxRow> all() { return new ArrayList<>(rows); }
}
