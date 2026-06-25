package com.northwood.search.infrastructure.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.northwood.search.application.GlobalEntityProjection;
import com.northwood.search.application.GlobalSearchQueryPort;
import com.northwood.search.application.dto.FacetBucket;
import com.northwood.search.application.dto.GlobalSearchResults;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

/**
 * Real-Elasticsearch test for the global cross-service read model (read model B):
 * entities of different types land in one {@code entities} index, a single fuzzy
 * query ranks across types, and the {@code entityType} facet counts per type —
 * the "one box, every bounded context" guarantee. Container + context config:
 * {@link AbstractSearchElasticsearchIT}.
 */
class GlobalSearchIT extends AbstractSearchElasticsearchIT {

    @Autowired
    private GlobalEntityProjection entities;

    @Autowired
    private GlobalSearchQueryPort globalSearch;

    @Autowired
    private ElasticsearchOperations elasticsearch;

    @BeforeEach
    void clearIndex() {
        elasticsearch.indexOps(EntityDocument.class).delete();
        elasticsearch.indexOps(EntityDocument.class).createWithMapping();
    }

    @Test
    void one_box_searches_across_bounded_contexts() {
        entities.upsertEntity(GlobalEntityProjection.TYPE_CUSTOMER, UUID.randomUUID(),
            "Acme Industries", "CUST-ACME", "ops@acme.example", "active");
        entities.upsertEntity(GlobalEntityProjection.TYPE_PRODUCT, UUID.randomUUID(),
            "Acme Widget", "FG-WIDGET-1", "finished_good", "active");
        entities.upsertEntity(GlobalEntityProjection.TYPE_SUPPLIER, UUID.randomUUID(),
            "Beta Supplies", "SUP-BETA", "sales@beta.example", "active");
        refresh();

        // One query, every context: "acme" matches the customer and the product.
        GlobalSearchResults acme = globalSearch.search("acme", null, 0, 20);
        assertThat(acme.totalHits()).isEqualTo(2);
        assertThat(acme.hits()).extracting(h -> h.entityType())
            .containsExactlyInAnyOrder(
                GlobalEntityProjection.TYPE_CUSTOMER, GlobalEntityProjection.TYPE_PRODUCT);

        // No query → all entities, with a per-type facet.
        GlobalSearchResults all = globalSearch.search(null, null, 0, 20);
        assertThat(all.totalHits()).isEqualTo(3);
        assertThat(all.entityTypeFacets()).extracting(FacetBucket::key)
            .contains(GlobalEntityProjection.TYPE_CUSTOMER,
                GlobalEntityProjection.TYPE_PRODUCT,
                GlobalEntityProjection.TYPE_SUPPLIER);
    }

    @Test
    void type_filter_restricts_to_one_entity_type() {
        entities.upsertEntity(GlobalEntityProjection.TYPE_CUSTOMER, UUID.randomUUID(),
            "Globex", "CUST-GLOBEX", "hi@globex.example", "active");
        entities.upsertEntity(GlobalEntityProjection.TYPE_SUPPLIER, UUID.randomUUID(),
            "Globex Logistics", "SUP-GLOBEX", "ship@globex.example", "active");
        refresh();

        GlobalSearchResults onlySuppliers =
            globalSearch.search("globex", GlobalEntityProjection.TYPE_SUPPLIER, 0, 20);
        assertThat(onlySuppliers.totalHits()).isEqualTo(1);
        assertThat(onlySuppliers.hits().get(0).code()).isEqualTo("SUP-GLOBEX");
    }

    private void refresh() {
        elasticsearch.indexOps(EntityDocument.class).refresh();
    }
}
