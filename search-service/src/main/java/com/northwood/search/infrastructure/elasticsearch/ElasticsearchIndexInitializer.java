package com.northwood.search.infrastructure.elasticsearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates the {@code products} index with the mapping derived from
 * {@link ProductDocument}'s annotations on startup, if it does not already
 * exist. The explicit mapping is load-bearing: the multi-field {@code text}
 * sub-fields that power fuzzy match and prefix autocomplete must be mapped
 * <em>before</em> the projectors index any document (ES dynamic mapping would
 * otherwise guess types from the first partial upsert).
 *
 * <p>Idempotent — a restart against an existing index is a no-op. To rebuild,
 * delete the index and replay {@code product.events} (the disposable-read-model
 * story).
 */
@Component
public class ElasticsearchIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);

    private final ElasticsearchOperations elasticsearch;

    public ElasticsearchIndexInitializer(ElasticsearchOperations elasticsearch) {
        this.elasticsearch = elasticsearch;
    }

    @Override
    public void run(ApplicationArguments args) {
        IndexOperations index = elasticsearch.indexOps(ProductDocument.class);
        if (index.exists()) {
            log.info("Elasticsearch index '{}' already exists — leaving as-is", ProductDocument.INDEX);
            return;
        }
        index.createWithMapping();
        log.info("created Elasticsearch index '{}' with mapping from ProductDocument", ProductDocument.INDEX);
    }
}
