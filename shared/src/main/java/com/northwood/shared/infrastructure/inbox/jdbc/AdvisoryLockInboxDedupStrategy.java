package com.northwood.shared.infrastructure.inbox.jdbc;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

/**
 * Option B dedup gate (the default, PostgreSQL-only): serialize the processing
 * of a given {@code (message_id, handler_name)} with a transaction-scoped
 * advisory lock, then check whether the inbox row already exists.
 *
 * <p>{@code pg_advisory_xact_lock} is held until the consumer's transaction
 * commits or rolls back — the same {@code @Transactional} boundary
 * {@code AbstractInboxHandler.handle} opens around check → apply → record. So a
 * concurrent redelivery of the same message (e.g. during a consumer-group
 * rebalance, when two threads briefly own the same partition) blocks at the
 * lock until the first transaction finishes and its inbox row is committed,
 * then sees the row and skips. This closes the check-then-act race that a bare
 * existence check has on its own. No schema change — works on the partitioned
 * {@code inbox_message} as-is.
 *
 * <p><strong>Two statements, not one.</strong> The lock is acquired in its own
 * statement <em>before</em> the {@code EXISTS} check, never folded into a single
 * CTE. Under {@code READ COMMITTED} a statement fixes its MVCC snapshot at
 * statement start; a one-statement lock+check would snapshot <em>before</em>
 * blocking on the lock and so could miss the row the prior writer committed
 * while we waited. Running the {@code EXISTS} as a second statement gives it a
 * fresh snapshot taken after the lock is held — i.e. after the prior writer
 * committed. See {@code docs/messaging.md}.
 */
final class AdvisoryLockInboxDedupStrategy implements InboxDedupStrategy {

    private static final String ACQUIRE_LOCK_SQL =
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";

    private static final String EXISTS_SQL =
        "SELECT EXISTS (SELECT 1 FROM inbox_message WHERE message_id = ? AND handler_name = ?)";

    private final JdbcTemplate jdbc;

    AdvisoryLockInboxDedupStrategy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean alreadyProcessed(UUID messageId, String handlerName) {
        // Statement 1: take (or wait for) the per-(message, consumer) advisory
        // lock; held until the surrounding transaction ends. ResultSetExtractor
        // discards the void result without tripping queryForObject's type check.
        jdbc.query(ACQUIRE_LOCK_SQL,
            (ResultSetExtractor<Void>) rs -> null,
            messageId + ":" + handlerName);

        // Statement 2: fresh snapshot now that the lock is held.
        Boolean exists = jdbc.queryForObject(EXISTS_SQL, Boolean.class, messageId, handlerName);
        return Boolean.TRUE.equals(exists);
    }
}
