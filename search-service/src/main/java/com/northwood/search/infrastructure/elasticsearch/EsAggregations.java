package com.northwood.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import com.northwood.search.application.dto.FacetBucket;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregation;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.core.SearchHits;

/**
 * Reads a named {@code terms} aggregation off a {@link SearchHits} into the
 * application's {@link FacetBucket} list — the one place that cracks the
 * Elasticsearch client's aggregate union types, shared by every {@code *QueryPort}
 * that returns facets.
 */
final class EsAggregations {

    private EsAggregations() {
    }

    static List<FacetBucket> termsFacet(SearchHits<?> hits, String aggregationName) {
        if (!(hits.getAggregations() instanceof ElasticsearchAggregations aggregations)) {
            return List.of();
        }
        ElasticsearchAggregation aggregation = aggregations.aggregationsAsMap().get(aggregationName);
        if (aggregation == null) {
            return List.of();
        }
        List<FacetBucket> buckets = new ArrayList<>();
        for (StringTermsBucket bucket : aggregation.aggregation().getAggregate().sterms().buckets().array()) {
            buckets.add(new FacetBucket(bucket.key().stringValue(), bucket.docCount()));
        }
        return buckets;
    }
}
