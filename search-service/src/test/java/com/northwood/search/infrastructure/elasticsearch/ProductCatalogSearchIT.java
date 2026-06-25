package com.northwood.search.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.search.application.ProductCatalogProjection;
import com.northwood.search.application.ProductSearchQueryPort;
import com.northwood.search.application.dto.FacetBucket;
import com.northwood.search.application.dto.ProductSearchCriteria;
import com.northwood.search.application.dto.ProductSearchResults;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

/**
 * Real-Elasticsearch test for the product catalog read model (read model A): a
 * {@code ProductCreated} projection produces a searchable doc, fuzzy search is
 * typo-tolerant, re-applying the same event stays idempotent (one doc), and the
 * terms facet returns bucket counts. Container + context config:
 * {@link AbstractSearchElasticsearchIT}.
 */
class ProductCatalogSearchIT extends AbstractSearchElasticsearchIT {

    @Autowired
    private ProductCatalogProjection products;

    @Autowired
    private ProductSearchQueryPort productSearch;

    @Autowired
    private ElasticsearchOperations elasticsearch;

    @BeforeEach
    void clearIndex() {
        elasticsearch.indexOps(ProductDocument.class).delete();
        elasticsearch.indexOps(ProductDocument.class).createWithMapping();
    }

    @Test
    void product_is_searchable_and_fuzzy_matches_a_typo() {
        UUID id = UUID.randomUUID();
        products.upsertProduct(id, "FG-LAPTOP-1", "Laptop", "finished_good", Instant.now());
        refresh();

        ProductSearchResults exact = productSearch.search(criteria("Laptop"));
        assertThat(exact.totalHits()).isEqualTo(1);
        assertThat(exact.hits().get(0).sku()).isEqualTo("FG-LAPTOP-1");

        // Fuzzy: a typo still matches (fuzziness AUTO).
        ProductSearchResults fuzzy = productSearch.search(criteria("labtop"));
        assertThat(fuzzy.totalHits()).isEqualTo(1);
    }

    @Test
    void re_applying_the_same_event_is_idempotent() {
        UUID id = UUID.randomUUID();
        products.upsertProduct(id, "FG-DESK-1", "Standing Desk", "finished_good", Instant.now());
        products.upsertProduct(id, "FG-DESK-1", "Standing Desk", "finished_good", Instant.now());
        refresh();

        ProductSearchResults results = productSearch.search(criteria("desk"));
        assertThat(results.totalHits()).isEqualTo(1);
    }

    @Test
    void terms_facet_returns_bucket_counts_per_product_type() {
        products.upsertProduct(UUID.randomUUID(), "FG-1", "Chair", "finished_good", Instant.now());
        products.upsertProduct(UUID.randomUUID(), "FG-2", "Table", "finished_good", Instant.now());
        products.upsertProduct(UUID.randomUUID(), "RM-1", "Oak Plank", "raw_material", Instant.now());
        refresh();

        ProductSearchResults results = productSearch.search(criteria(null));
        assertThat(results.totalHits()).isEqualTo(3);
        List<FacetBucket> typeFacets = results.productTypeFacets();
        assertThat(typeFacets).extracting(FacetBucket::key)
            .contains("finished_good", "raw_material");
        assertThat(typeFacets).filteredOn(b -> b.key().equals("finished_good"))
            .first().extracting(FacetBucket::count).isEqualTo(2L);
    }

    private void refresh() {
        elasticsearch.indexOps(ProductDocument.class).refresh();
    }

    private static ProductSearchCriteria criteria(String q) {
        return new ProductSearchCriteria(q, null, null, null, null, 0, 20);
    }
}
