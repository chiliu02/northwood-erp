package com.northwood.shared.application.inbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;

/**
 * Inbox row. Used to make event consumption idempotent: a consumer records
 * (message_id, handler_name) before applying the event, so a redelivery
 * doesn't double-apply.
 */
public final class InboxRow {

    // ------------------------------------------------------------
    // Status constants — wire-format strings stored in
    // <service>.inbox_message.status, mirroring its
    // CHECK (status IN ('processed', 'failed')) set. A row is recorded
    // straight as 'processed'; 'failed' is schema-prep for a future
    // dead-letter / poison-message path. Internal messaging plumbing, so
    // (like OutboxRow) no <service>-events constant — but Java-side use
    // goes through these, never a bare literal.
    // ------------------------------------------------------------
    public static final String PROCESSED = "processed";

    /** Schema-prep — not currently produced by Java. */
    public static final String FAILED = "failed";

    @Id
    @Column("inbox_message_id")
    private UUID inboxMessageId;

    @Column("message_id")
    private UUID messageId;

    @Column("handler_name")
    private String handlerName;

    @Column("event_type")
    private String eventType;

    @Column("event_version")
    private int eventVersion;

    @Column("source_sequence_number")
    private Long sourceSequenceNumber;

    private String payload;

    private String status;

    @Column("processed_at")
    private Instant processedAt;

    public InboxRow() {}

    public static InboxRow processed(
        UUID inboxMessageId,
        UUID messageId,
        String handlerName,
        String eventType,
        int eventVersion,
        Long sourceSequenceNumber,
        String payloadJson
    ) {
        InboxRow r = new InboxRow();
        r.inboxMessageId = inboxMessageId;
        r.messageId = messageId;
        r.handlerName = handlerName;
        r.eventType = eventType;
        r.eventVersion = eventVersion;
        r.sourceSequenceNumber = sourceSequenceNumber;
        r.payload = payloadJson;
        r.status = PROCESSED;
        r.processedAt = Instant.now();
        return r;
    }

    public UUID getInboxMessageId()       { return inboxMessageId; }
    public UUID getMessageId()            { return messageId; }
    public String getConsumerName()       { return handlerName; }
    public String getEventType()          { return eventType; }
    public int getEventVersion()          { return eventVersion; }
    public Long getSourceSequenceNumber() { return sourceSequenceNumber; }
    public String getPayload()            { return payload; }
    public String getStatus()             { return status; }
    public Instant getProcessedAt()       { return processedAt; }
}
