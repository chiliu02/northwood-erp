package com.northwood.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.northwood.search.application.GlobalSearchQueryPort;
import com.northwood.search.application.dto.EntitySearchView;
import com.northwood.search.application.dto.GlobalSearchResults;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Elasticsearch implementation of {@link GlobalSearchQueryPort}. One
 * {@code multi_match} (fuzzy) over {@code title}/{@code code} across every entity
 * type, an optional {@code entityType} term filter, and a {@code terms}
 * aggregation that counts hits per type — the "one box, every bounded context"
 * query relational read models can't express.
 */
@Repository
public class ElasticsearchGlobalSearchQueryPort implements GlobalSearchQueryPort {

    private static final String AGG_ENTITY_TYPE = "entityType";

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchGlobalSearchQueryPort(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public GlobalSearchResults search(String q, String entityType, int page, int size) {
        Query query = Query.of(b -> b.bool(bool -> {
            if (StringUtils.hasText(q)) {
                bool.must(m -> m.multiMatch(mm -> mm
                    .query(q)
                    .fields("title^3", "code.text", "code^2", "subtitle")
                    .fuzziness("AUTO")));
            } else {
                bool.must(m -> m.matchAll(ma -> ma));
            }
            if (StringUtils.hasText(entityType)) {
                bool.filter(f -> f.term(t -> t.field("entityType").value(entityType)));
            }
            return bool;
        }));

        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(query)
            .withPageable(PageRequest.of(Math.max(page, 0), Math.max(size, 1)))
            .withAggregation(AGG_ENTITY_TYPE, Aggregation.of(a -> a.terms(t -> t.field("entityType"))))
            .build();

        SearchHits<EntityDocument> hits = elasticsearch.search(nativeQuery, EntityDocument.class);

        List<EntitySearchView> views = new ArrayList<>();
        for (SearchHit<EntityDocument> hit : hits) {
            EntityDocument d = hit.getContent();
            views.add(new EntitySearchView(
                d.entityType(), d.entityId(), d.title(), d.code(), d.subtitle(), d.status(), hit.getScore()));
        }

        return new GlobalSearchResults(
            hits.getTotalHits(), views, EsAggregations.termsFacet(hits, AGG_ENTITY_TYPE));
    }
}
