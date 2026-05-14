package com.northwood.shared.application.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port through which the audit REST controller reads {@link AuditEntry} rows
 * from a service's local {@code outbox_message} table. The shared
 * {@code JdbcAuditQueryAdapter} is the production implementation; tests can
 * substitute an in-memory fake.
 *
 * <p>Filtering is permissive: any of {@code aggregateId} / {@code from} /
 * {@code to} may be null. The {@code limit} is mandatory and the caller
 * (controller) is responsible for clamping it.
 */
public interface AuditQueryPort {

    List<AuditEntry> find(UUID aggregateId, Instant from, Instant to, int limit);
}
