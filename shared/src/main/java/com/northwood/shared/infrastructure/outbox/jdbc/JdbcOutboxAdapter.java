package com.northwood.shared.infrastructure.outbox.jdbc;

import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * JDBC adapter for {@link OutboxPort}, backed by a service's
 * {@code outbox_message} table.
 *
 * <p>Service-agnostic: SQL references {@code outbox_message} unqualified, and
 * each service's connection pool sets {@code search_path = <service>, shared}
 * via {@code spring.datasource.hikari.connection-init-sql}, so the unqualified
 * name resolves to {@code <service>.outbox_message} automatically. Registered
 * as a Spring bean via {@link JdbcOutboxAutoConfiguration}.
 *
 * <p>{@link #findPending} uses {@code FOR UPDATE SKIP LOCKED} so multiple
 * publisher workers (a future scale-out scenario) don't fight over the same
 * rows.
 */
public class JdbcOutboxAdapter implements OutboxPort {

    private final JdbcTemplate jdbc;

    public JdbcOutboxAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<OutboxRow> findPending(int limit) {
        return jdbc.query(
            """
            SELECT outbox_message_id, sequence_number, aggregate_type, aggregate_id,
                   event_type, event_version, payload, headers,
                   correlation_id, causation_id, actor_user_id,
                   status, retry_count, last_error,
                   created_at, published_at
            FROM outbox_message
            WHERE status IN ('pending', 'failed')
            ORDER BY sequence_number
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            ROW_MAPPER, limit
        );
    }

    @Override
    public void update(OutboxRow row) {
        jdbc.update(
            """
            UPDATE outbox_message SET
                status = ?, retry_count = ?, last_error = ?, published_at = ?
            WHERE outbox_message_id = ? AND created_at = ?
            """,
            row.getStatus(),
            row.getRetryCount(),
            row.getLastError(),
            row.getPublishedAt() == null ? null : Timestamp.from(row.getPublishedAt()),
            row.getOutboxMessageId(),
            Timestamp.from(row.getCreatedAt())
        );
    }

    @Override
    public void appendPending(OutboxRow row) {
        // The outbox_message.headers column is `JSONB NOT NULL DEFAULT '{}'`.
        // Aggregate-side INSERTs in the per-service Jdbc*Repository classes
        // omit the column entirely so the default fires; this shared
        // appendPending path (used by saga workers and other non-aggregate
        // emitters) lists headers explicitly, so a null reference would bypass
        // the default and trip the NOT NULL constraint. Coerce null → '{}' to
        // match what the read path would surface.
        jdbc.update(
            """
            INSERT INTO outbox_message (
                outbox_message_id, aggregate_type, aggregate_id,
                event_type, event_version, payload, headers,
                correlation_id, causation_id, actor_user_id, status
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, 'pending')
            """,
            row.getOutboxMessageId(),
            row.getAggregateType(),
            row.getAggregateId(),
            row.getEventType(),
            row.getEventVersion(),
            row.getPayload(),
            row.getHeaders() == null ? "{}" : row.getHeaders(),
            row.getCorrelationId(),
            row.getCausationId(),
            row.getActorUserId()
        );
    }

    private static final RowMapper<OutboxRow> ROW_MAPPER = (rs, n) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return OutboxRow.fromDb(
            rs.getObject("outbox_message_id", UUID.class),
            rs.getObject("sequence_number", Long.class),
            rs.getString("aggregate_type"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("event_type"),
            rs.getInt("event_version"),
            rs.getString("payload"),
            rs.getString("headers"),
            rs.getObject("correlation_id", UUID.class),
            rs.getObject("causation_id", UUID.class),
            rs.getString("actor_user_id"),
            rs.getString("status"),
            rs.getInt("retry_count"),
            rs.getString("last_error"),
            createdAt == null ? null : createdAt.toInstant(),
            publishedAt == null ? null : publishedAt.toInstant()
        );
    };
}
