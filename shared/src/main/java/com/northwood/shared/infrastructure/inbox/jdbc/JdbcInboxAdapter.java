package com.northwood.shared.infrastructure.inbox.jdbc;

import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.inbox.InboxRow;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC adapter for {@link InboxPort}, backed by a service's
 * {@code inbox_message} table. The dedup check in {@link #alreadyProcessed} is
 * delegated to a configurable {@link InboxDedupStrategy} (the "gate");
 * {@link #recordProcessed} writes the audit/dedup row the gate later sees.
 * Together they give exactly-once-effect semantics inside the consumer's
 * transaction.
 *
 * <p>The gate is selected by {@code northwood.inbox.dedup-strategy} (default
 * {@code advisory-lock}) and is purely an infrastructure concern: the
 * application layer ({@code AbstractInboxHandler}) only calls
 * {@code alreadyProcessed} / {@code recordProcessed} and never sees which
 * mechanism is in force. Full design: {@code docs/messaging.md}.
 *
 * <p>Service-agnostic: SQL references {@code inbox_message} unqualified, and
 * each service's connection pool sets {@code search_path = <service>, shared}
 * via {@code spring.datasource.hikari.connection-init-sql}, so the unqualified
 * name resolves to {@code <service>.inbox_message} automatically. Registered
 * as a Spring bean via {@link JdbcInboxAutoConfiguration}.
 */
public class JdbcInboxAdapter implements InboxPort {

    private final JdbcTemplate jdbc;
    private final InboxDedupStrategy dedup;

    public JdbcInboxAdapter(JdbcTemplate jdbc, InboxDedupStrategy dedup) {
        this.jdbc = jdbc;
        this.dedup = dedup;
    }

    @Override
    public boolean alreadyProcessed(UUID messageId, String handlerName) {
        return dedup.alreadyProcessed(messageId, handlerName);
    }

    @Override
    public void recordProcessed(InboxRow row) {
        jdbc.update("""
            INSERT INTO inbox_message (
                inbox_message_id, message_id, handler_name, event_type, event_version,
                source_sequence_number, payload, status, processed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """,
            row.getInboxMessageId(),
            row.getMessageId(),
            row.getHandlerName(),
            row.getEventType(),
            row.getEventVersion(),
            row.getSourceSequenceNumber(),
            row.getPayload(),
            row.getStatus(),
            java.sql.Timestamp.from(row.getProcessedAt())
        );
    }
}
