package com.northwood.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.northwood.search.application.ProductSearchQueryPort;
import com.northwood.search.application.dto.ProductSearchCriteria;
import com.northwood.search.application.dto.ProductSearchResults;
import com.northwood.search.application.dto.ProductSearchView;
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
 * Elasticsearch implementation of {@link ProductSearchQueryPort} — the search
 * capabilities a relational read model can't do well:
 *
 * <ul>
 *   <li><b>Full-text + fuzzy:</b> {@code multi_match} over {@code name} (boosted),
 *       {@code sku.text} and {@code name.keyword} with {@code fuzziness=AUTO}, so
 *       "labtop" still finds "Laptop". Results are relevance-<em>scored</em>, not
 *       just filtered.</li>
 *   <li><b>Filters:</b> {@code productType} / {@code status} term filters and a
 *       {@code salesPrice} range — applied as bool filters (no score impact).</li>
 *   <li><b>Facets:</b> {@code terms} aggregations on {@code productType} and
 *       {@code status} return per-bucket counts for a faceted filter UI.</li>
 *   <li><b>Autocomplete:</b> {@link #suggest} runs {@code match_phrase_prefix}
 *       over {@code name.text} for type-ahead.</li>
 * </ul>
 */
@Repository
public class ElasticsearchProductSearchQueryPort implements ProductSearchQueryPort {

    private static final String AGG_PRODUCT_TYPE = "productType";
    private static final String AGG_STATUS = "status";

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchProductSearchQueryPort(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public ProductSearchResults search(ProductSearchCriteria criteria) {
        Query query = Query.of(q -> q.bool(b -> {
            if (StringUtils.hasText(criteria.q())) {
                b.must(m -> m.multiMatch(mm -> mm
                    .query(criteria.q())
                    .fields("name^3", "name.text", "sku.text")
                    .fuzziness("AUTO")));
            } else {
                b.must(m -> m.matchAll(ma -> ma));
            }
            if (StringUtils.hasText(criteria.productType())) {
                b.filter(f -> f.term(t -> t.field("productType").value(criteria.productType())));
            }
            if (StringUtils.hasText(criteria.status())) {
                b.filter(f -> f.term(t -> t.field("status").value(criteria.status())));
            }
            if (criteria.minPrice() != null || criteria.maxPrice() != null) {
                b.filter(f -> f.range(r -> r.number(n -> {
                    n.field("salesPrice");
                    if (criteria.minPrice() != null) n.gte(criteria.minPrice().doubleValue());
                    if (criteria.maxPrice() != null) n.lte(criteria.maxPrice().doubleValue());
                    return n;
                })));
            }
            return b;
        }));

        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(query)
            .withPageable(PageRequest.of(Math.max(criteria.page(), 0), Math.max(criteria.size(), 1)))
            .withAggregation(AGG_PRODUCT_TYPE, termsAggregation("productType"))
            .withAggregation(AGG_STATUS, termsAggregation("status"))
            .build();

        SearchHits<ProductDocument> hits = elasticsearch.search(nativeQuery, ProductDocument.class);

        List<ProductSearchView> views = new ArrayList<>();
        for (SearchHit<ProductDocument> hit : hits) {
            ProductDocument d = hit.getContent();
            views.add(new ProductSearchView(
                d.productId(), d.sku(), d.name(), d.productType(), d.status(), d.salesPrice(), hit.getScore()));
        }

        return new ProductSearchResults(
            hits.getTotalHits(),
            views,
            EsAggregations.termsFacet(hits, AGG_PRODUCT_TYPE),
            EsAggregations.termsFacet(hits, AGG_STATUS)
        );
    }

    @Override
    public List<String> suggest(String prefix, int limit) {
        if (!StringUtils.hasText(prefix)) {
            return List.of();
        }
        Query query = Query.of(q -> q.matchPhrasePrefix(m -> m.field("name.text").query(prefix)));
        NativeQuery nativeQuery = NativeQuery.builder()
            .withQuery(query)
            .withPageable(PageRequest.of(0, Math.max(limit, 1)))
            .build();
        SearchHits<ProductDocument> hits = elasticsearch.search(nativeQuery, ProductDocument.class);
        List<String> names = new ArrayList<>();
        for (SearchHit<ProductDocument> hit : hits) {
            String name = hit.getContent().name();
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static Aggregation termsAggregation(String field) {
        return Aggregation.of(a -> a.terms(t -> t.field(field)));
    }
}
