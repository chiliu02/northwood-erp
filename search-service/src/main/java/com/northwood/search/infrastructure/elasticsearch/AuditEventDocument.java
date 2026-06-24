package com.northwood.search.infrastructure.elasticsearch;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

/**
 * The {@code audit-events} Elasticsearch read model (read model C) — every
 * {@code *.events} message appended as one document, the broadest read model off
 * the log. Powers full-text + time-range audit search and {@code terms}
 * aggregations (events per service / type), and is the index Kibana dashboards
 * over. Retires the BFF's in-memory cross-service audit fan-out.
 *
 * <p>Document id is the {@code eventId} (globally unique) so re-delivery is an
 * idempotent overwrite. {@code occurredAt} is stored as epoch-millis mapped to a
 * {@code date} field — robust (no string date parsing) yet date-typed for Kibana
 * histograms. {@code payload} is the raw event JSON, kept as analyzed text for
 * full-text search.
 */
@Document(indexName = AuditEventDocument.INDEX)
public record AuditEventDocument(
    @Id String eventId,
    @Field(type = FieldType.Keyword) String eventType,
    @Field(type = FieldType.Keyword) String aggregateType,
    @Field(type = FieldType.Keyword) String aggregateId,
    @Field(type = FieldType.Integer) int eventVersion,
    @Field(type = FieldType.Keyword) String sourceService,
    @Field(type = FieldType.Keyword) String actorUserId,
    @Field(type = FieldType.Keyword) String correlationId,
    @Field(type = FieldType.Date, format = DateFormat.epoch_millis) long occurredAtEpochMs,
    @Field(type = FieldType.Text) String payload
) {

    public static final String INDEX = "audit-events";
}
