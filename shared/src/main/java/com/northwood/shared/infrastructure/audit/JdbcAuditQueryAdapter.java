package com.northwood.shared.infrastructure.audit;

import com.northwood.shared.application.audit.AuditEntry;
import com.northwood.shared.application.audit.AuditQueryPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Reads {@code outbox_message} rows for the audit-log endpoint. SQL is
 * service-agnostic (unqualified {@code outbox_message}; the per-service
 * search_path resolves to the local table) — the same adapter wires into
 * every service via {@link AuditAutoConfiguration}.
 *
 * <p>Filtering is permissive on purpose: the controller is a generic GET,
 * so any of the three filters can be omitted. The audit endpoint never
 * exposes the event payload (privacy + payload size) — only routing
 * metadata + actor.
 */
public class JdbcAuditQueryAdapter implements AuditQueryPort {

    private final JdbcTemplate jdbc;
    private final String serviceName;

    public JdbcAuditQueryAdapter(JdbcTemplate jdbc, String serviceName) {
        this.jdbc = jdbc;
        this.serviceName = serviceName;
    }

    @Override
    public List<AuditEntry> find(UUID aggregateId, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("""
            SELECT outbox_message_id, sequence_number, aggregate_type, aggregate_id,
                   event_type, actor_user_id, correlation_id, created_at,
                   -- Surface the W3C trace ID stamped by OutboxPublisher
                   -- as a dedicated column on the API row. SUBSTRING
                   -- extracts the 32-char traceId out of the
                   -- "00-<traceId>-<spanId>-<flags>" header value, or NULL if
                   -- the header is absent or malformed.
                   SUBSTRING(headers->>'traceparent' FROM 4 FOR 32) AS trace_id
            FROM outbox_message
            WHERE 1=1
            """);
        List<Object> args = new ArrayList<>();
        if (aggregateId != null) {
            sql.append(" AND aggregate_id = ?");
            args.add(aggregateId);
        }
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at <= ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY sequence_number DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), rowMapper(), args.toArray());
    }

    private RowMapper<AuditEntry> rowMapper() {
        return (rs, n) -> {
            UUID corr = rs.getObject("correlation_id", UUID.class);
            String traceId = rs.getString("trace_id");
            // SUBSTRING gives us 32 chars even when the header is shorter
            // (degenerate cases — e.g. NULL → NULL, "" → ""). Normalise empty
            // back to NULL so the API contract stays simple ("present or
            // null").
            if (traceId != null && traceId.length() != 32) {
                traceId = null;
            }
            return new AuditEntry(
                rs.getObject("outbox_message_id", UUID.class),
                rs.getObject("sequence_number", Long.class),
                serviceName,
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("actor_user_id"),
                corr == null ? null : corr.toString(),
                traceId,
                rs.getTimestamp("created_at").toInstant()
            );
        };
    }
}
