package com.northwood.search.infrastructure.elasticsearch;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates each Elasticsearch read-model index with the mapping derived from its
 * {@code @Document} record's annotations on startup, if it does not already
 * exist ({@code products} = read model A, {@code entities} = read model B,
 * {@code audit-events} = read model C). The
 * explicit mapping is load-bearing: the multi-field {@code text} sub-fields that
 * power fuzzy match and prefix autocomplete must be mapped <em>before</em> the
 * projectors index any document (ES dynamic mapping would otherwise guess types
 * from the first partial upsert).
 *
 * <p>Idempotent — a restart against an existing index is a no-op. To rebuild,
 * delete the index and replay the source topics (the disposable-read-model
 * story).
 */
@Component
public class ElasticsearchIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);

    private static final List<Class<?>> INDEXED_DOCUMENTS = List.of(
        ProductDocument.class,
        EntityDocument.class,
        AuditEventDocument.class
    );

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchIndexInitializer(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Class<?> documentType : INDEXED_DOCUMENTS) {
            IndexOperations index = elasticsearch.indexOps(documentType);
            if (index.exists()) {
                log.info("Elasticsearch index for {} already exists — leaving as-is", documentType.getSimpleName());
                continue;
            }
            index.createWithMapping();
            log.info("created Elasticsearch index with mapping from {}", documentType.getSimpleName());
        }
    }
}
