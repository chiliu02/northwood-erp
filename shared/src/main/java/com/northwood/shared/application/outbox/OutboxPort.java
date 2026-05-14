package com.northwood.shared.application.outbox;

import java.util.List;

/**
 * Port through which {@link OutboxPublisher} reads and updates a service's
 * outbox table. Each service provides its own implementation backed by
 * Spring Data JDBC, JdbcTemplate, or whatever it likes — just always against
 * its OWN schema's outbox_message table.
 */
public interface OutboxPort {

    /**
     * Return at most {@code limit} pending rows in {@code sequence_number}
     * order. Implementations should use {@code FOR UPDATE SKIP LOCKED} so
     * multiple publisher workers don't fight over the same rows.
     */
    List<OutboxRow> findPending(int limit);

    /**
     * Update an existing outbox row's status / retry / publishedAt — used by
     * {@link OutboxPublisher} after a successful publish or to mark failure.
     */
    void save(OutboxRow row);

    /**
     * Insert a new {@code 'pending'} outbox row in the same transaction as
     * the caller. Used by application services and inbox handlers that need
     * to emit an event but don't have an aggregate to drain through (e.g.
     * a CQRS-style write or a multi-aggregate dispatch). Aggregate-driven
     * emission still goes through the aggregate's {@code pendingEvents} and
     * the per-service repository's {@code save}.
     */
    void appendPending(OutboxRow row);
}
