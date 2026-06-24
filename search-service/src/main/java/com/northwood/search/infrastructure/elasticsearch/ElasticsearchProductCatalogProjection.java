package com.northwood.search.infrastructure.elasticsearch;

import com.northwood.search.application.ProductCatalogProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Repository;

/**
 * Elasticsearch implementation of {@link ProductCatalogProjection}. Every write
 * is a <strong>partial upsert keyed by the product id</strong>
 * ({@code docAsUpsert} = insert the doc if absent, else merge the given fields),
 * which gives both idempotency (re-applying an event is a no-op merge) and order
 * tolerance (a price change arriving before {@code ProductCreated} creates a stub
 * the create later backfills, and vice versa).
 */
@Repository
public class ElasticsearchProductCatalogProjection implements ProductCatalogProjection {

    private static final IndexCoordinates INDEX = IndexCoordinates.of(ProductDocument.INDEX);

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchProductCatalogProjection(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public void upsertProduct(UUID productId, String sku, String name, String productType, Instant occurredAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sku", sku);
        fields.put("name", name);
        fields.put("productType", productType);
        fields.put("status", ProductDocument.STATUS_ACTIVE);
        partialUpsert(productId, fields);
    }

    @Override
    public void updateSalesPrice(UUID productId, BigDecimal salesPrice, String currencyCode) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("salesPrice", salesPrice == null ? null : salesPrice.doubleValue());
        fields.put("currencyCode", currencyCode);
        partialUpsert(productId, fields);
    }

    @Override
    public void markDiscontinued(UUID productId) {
        partialUpsert(productId, Map.of("status", ProductDocument.STATUS_DISCONTINUED));
    }

    private void partialUpsert(UUID productId, Map<String, Object> fields) {
        UpdateQuery update = UpdateQuery.builder(productId.toString())
            .withDocument(Document.from(fields))
            .withDocAsUpsert(true)
            .build();
        elasticsearch.update(update, INDEX);
    }
}
