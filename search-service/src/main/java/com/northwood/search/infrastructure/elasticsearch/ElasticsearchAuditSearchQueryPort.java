package com.northwood.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.northwood.search.application.AuditSearchQueryPort;
import com.northwood.search.application.dto.AuditEventView;
import com.northwood.search.application.dto.AuditSearchResults;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Elasticsearch implementation of {@link AuditSearchQueryPort}. Full-text over
 * the event {@code payload} (fuzzy) with {@code sourceService}/{@code eventType}
 * filters, newest-first, plus {@code terms} aggregations for "events per service
 * / type / aggregate-type" — the analytics relational read models can't do, and
 * the same index Kibana charts over time.
 */
@Repository
public class ElasticsearchAuditSearchQueryPort implements AuditSearchQueryPort {

    private static final String AGG_SERVICE = "sourceService";
    private static final String AGG_EVENT_TYPE = "eventType";
    private static final String AGG_AGGREGATE_TYPE = "aggregateType";

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchAuditSearchQueryPort(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public AuditSearchResults search(String q, String service, String eventType, int page, int size) {
        Query query = Query.of(b -> b.bool(bool -> {
            if (StringUtils.hasText(q)) {
                bool.must(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields("payload", "eventType", "aggregateType")
                    .fuzziness("AUTO")));
            } else {
                bool.must(m -> m.matchAll(ma -> ma));
            }
            if (StringUtils.hasText(service)) {
                bool.filter(f -> f.term(t -> t.field("sourceService").value(service)));
            }
            if (StringUtils.hasText(eventType)) {
                bool.filter(f -> f.term(t -> t.field("eventType").value(eventType)));
            }
            return bool;
        }));

        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(query)
            .withPageable(PageRequest.of(Math.max(page, 0), Math.max(size, 1)))
            .withSort(Sort.by(Sort.Direction.DESC, "occurredAtEpochMs"))
            .withAggregation(AGG_SERVICE, Aggregation.of(a -> a.terms(t -> t.field("sourceService"))))
            .withAggregation(AGG_EVENT_TYPE, Aggregation.of(a -> a.terms(t -> t.field("eventType"))))
            .withAggregation(AGG_AGGREGATE_TYPE, Aggregation.of(a -> a.terms(t -> t.field("aggregateType"))))
            .build();

        SearchHits<AuditEventDocument> hits = elasticsearch.search(nativeQuery, AuditEventDocument.class);

        List<AuditEventView> views = new ArrayList<>();
        for (SearchHit<AuditEventDocument> hit : hits) {
            AuditEventDocument d = hit.getContent();
            views.add(new AuditEventView(
                d.eventId(), d.eventType(), d.aggregateType(), d.aggregateId(),
                d.sourceService(), d.actorUserId(), d.correlationId(),
                Instant.ofEpochMilli(d.occurredAtEpochMs()), d.payload()));
        }

        return new AuditSearchResults(
            hits.getTotalHits(),
            views,
            EsAggregations.termsFacet(hits, AGG_SERVICE),
            EsAggregations.termsFacet(hits, AGG_EVENT_TYPE),
            EsAggregations.termsFacet(hits, AGG_AGGREGATE_TYPE)
        );
    }
}
