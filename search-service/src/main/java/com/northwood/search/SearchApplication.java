package com.northwood.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Search read side: an inbox-only consumer that projects domain events into
 * Elasticsearch indices and serves search/relevance/facet/autocomplete queries
 * SQL read models can't. No system of record — the indices are derived from the
 * event log and rebuildable by replay (see docs/elasticsearch.md).
 */
@SpringBootApplication
public class SearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
