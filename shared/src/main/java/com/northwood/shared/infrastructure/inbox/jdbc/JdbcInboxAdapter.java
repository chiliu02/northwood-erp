package com.northwood.shared.infrastructure.inbox.jdbc;

import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.inbox.InboxRow;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC adapter for {@link InboxPort}, backed by a service's
 * {@code inbox_message} table. The unique constraint on
 * {@code (message_id, consumer_name, processed_at)} plus the dedupe check in
 * {@link #alreadyProcessed} give exactly-once-effect semantics inside the
 * consumer's transaction.
 *
 * <p>Service-agnostic: SQL references {@code inbox_message} unqualified, and
 * each service's connection pool sets {@code search_path = <service>, shared}
 * via {@code spring.datasource.hikari.connection-init-sql}, so the unqualified
 * name resolves to {@code <service>.inbox_message} automatically. Registered
 * as a Spring bean via {@link JdbcInboxAutoConfiguration}.
 */
public class JdbcInboxAdapter implements InboxPort {

    private final JdbcTemplate jdbc;

    public JdbcInboxAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean alreadyProcessed(UUID messageId, String consumerName) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM inbox_message WHERE message_id = ? AND consumer_name = ?",
            Integer.class,
            messageId, consumerName
        );
        return count != null && count > 0;
    }

    @Override
    public void recordProcessed(InboxRow row) {
        jdbc.update("""
            INSERT INTO inbox_message (
                inbox_message_id, message_id, consumer_name, event_type, event_version,
                source_sequence_number, payload, status, processed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """,
            row.getInboxMessageId(),
            row.getMessageId(),
            row.getConsumerName(),
            row.getEventType(),
            row.getEventVersion(),
            row.getSourceSequenceNumber(),
            row.getPayload(),
            row.getStatus(),
            java.sql.Timestamp.from(row.getProcessedAt())
        );
    }
}
