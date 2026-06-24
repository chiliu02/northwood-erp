package com.northwood.search.infrastructure.elasticsearch;

import com.northwood.search.application.GlobalEntityProjection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.stereotype.Repository;

/**
 * Elasticsearch implementation of {@link GlobalEntityProjection}. Writes a
 * partial upsert keyed by {@code <entityType>:<aggregateId>} (docAsUpsert), so
 * re-applying an event is idempotent and an out-of-order arrival self-heals.
 */
@Repository
public class ElasticsearchGlobalEntityProjection implements GlobalEntityProjection {

    private static final IndexCoordinates INDEX = IndexCoordinates.of(EntityDocument.INDEX);

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchGlobalEntityProjection(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public void upsertEntity(String entityType, UUID id, String title, String code, String subtitle, String status) {
        String docId = entityType + ":" + id;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("entityType", entityType);
        fields.put("entityId", id.toString());
        fields.put("title", title);
        fields.put("code", code);
        fields.put("subtitle", subtitle);
        fields.put("status", status);
        UpdateQuery update = UpdateQuery.builder(docId)
            .withDocument(Document.from(fields))
            .withDocAsUpsert(true)
            .build();
        elasticsearch.update(update, INDEX);
    }
}
