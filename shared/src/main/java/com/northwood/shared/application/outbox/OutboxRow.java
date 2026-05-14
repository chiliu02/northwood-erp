package com.northwood.shared.application.outbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

/**
 * Outbox row. Each service has its own outbox_message table (the schema is
 * per-context) but the row shape is identical across services, so this class
 * maps any of them. The actual table name is configured per-service via the
 * repository's {@code @Table} annotation in the service's infrastructure layer.
 *
 * <p>Insertions happen in the same transaction as the aggregate write. A
 * separate publisher (see {@link OutboxPublisher}) drains rows where
 * {@code status = 'pending'} and publishes them to the bus, ordered by
 * {@code sequence_number}.
 */
public final class OutboxRow {

    // ------------------------------------------------------------
    // Status constants — wire-format strings stored in
    // <service>.outbox_message.status. Lifecycle: pending → published
    // (drained by OutboxPublisher) or pending → failed (publisher error
    // with retry metadata; failed rows are picked up again next tick).
    // ------------------------------------------------------------
    public static final String PENDING = "pending";
    public static final String PUBLISHED = "published";
    public static final String FAILED = "failed";

    @Id
    @Column("outbox_message_id")
    private UUID outboxMessageId;

    @Column("sequence_number")
    private Long sequenceNumber;

    @Column("aggregate_type")
    private String aggregateType;

    @Column("aggregate_id")
    private UUID aggregateId;

    @Column("event_type")
    private String eventType;

    @Column("event_version")
    private int eventVersion;

    private String payload;

    private String headers;

    @Column("correlation_id")
    private UUID correlationId;

    @Column("causation_id")
    private UUID causationId;

    @Column("actor_user_id")
    private String actorUserId;

    private String status;

    @Column("retry_count")
    private int retryCount;

    @Column("last_error")
    private String lastError;

    @Column("created_at")
    private Instant createdAt;

    @Column("published_at")
    private Instant publishedAt;

    public OutboxRow() {}

    public static OutboxRow pending(
        UUID outboxMessageId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        String payloadJson,
        String headersJson,
        UUID correlationId,
        UUID causationId,
        String actorUserId
    ) {
        OutboxRow row = new OutboxRow();
        row.outboxMessageId = outboxMessageId;
        row.aggregateType = aggregateType;
        row.aggregateId = aggregateId;
        row.eventType = eventType;
        row.eventVersion = eventVersion;
        row.payload = payloadJson;
        row.headers = headersJson;
        row.correlationId = correlationId;
        row.causationId = causationId;
        row.actorUserId = actorUserId;
        row.status = PENDING;
        row.retryCount = 0;
        row.createdAt = Instant.now();
        return row;
    }

    /**
     * Reconstitute a row read from {@code <service>.outbox_message}. Used by
     * per-service {@link OutboxPort} implementations.
     */
    public static OutboxRow fromDb(
        UUID outboxMessageId,
        Long sequenceNumber,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        String payloadJson,
        String headersJson,
        UUID correlationId,
        UUID causationId,
        String actorUserId,
        String status,
        int retryCount,
        String lastError,
        Instant createdAt,
        Instant publishedAt
    ) {
        OutboxRow row = new OutboxRow();
        row.outboxMessageId = outboxMessageId;
        row.sequenceNumber = sequenceNumber;
        row.aggregateType = aggregateType;
        row.aggregateId = aggregateId;
        row.eventType = eventType;
        row.eventVersion = eventVersion;
        row.payload = payloadJson;
        row.headers = headersJson;
        row.correlationId = correlationId;
        row.causationId = causationId;
        row.actorUserId = actorUserId;
        row.status = status;
        row.retryCount = retryCount;
        row.lastError = lastError;
        row.createdAt = createdAt;
        row.publishedAt = publishedAt;
        return row;
    }

    public UUID getOutboxMessageId()         { return outboxMessageId; }
    public Long getSequenceNumber()          { return sequenceNumber; }
    public String getAggregateType()         { return aggregateType; }
    public UUID getAggregateId()             { return aggregateId; }
    public String getEventType()             { return eventType; }
    public int getEventVersion()             { return eventVersion; }
    public String getPayload()               { return payload; }
    public String getHeaders()               { return headers; }
    public UUID getCorrelationId()           { return correlationId; }
    public UUID getCausationId()             { return causationId; }
    public String getActorUserId()           { return actorUserId; }
    public String getStatus()                { return status; }
    public int getRetryCount()               { return retryCount; }
    public String getLastError()             { return lastError; }
    public Instant getCreatedAt()            { return createdAt; }
    public Instant getPublishedAt()          { return publishedAt; }

    public void markPublished() {
        this.status = PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.status = FAILED;
        this.retryCount += 1;
        this.lastError = error;
    }
}
