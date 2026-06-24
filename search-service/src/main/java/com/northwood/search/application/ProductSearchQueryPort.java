package com.northwood.search.application;

import com.northwood.search.application.dto.ProductSearchCriteria;
import com.northwood.search.application.dto.ProductSearchResults;
import java.util.List;

/**
 * Read side of the {@code products} index — the search capabilities SQL read
 * models can't do well: full-text + fuzzy relevance ranking, terms facets, and
 * prefix autocomplete. Implemented over Elasticsearch in
 * {@code infrastructure/elasticsearch/}.
 */
public interface ProductSearchQueryPort {

    /** Full-text fuzzy search with optional filters; returns ranked hits + facet bucket counts. */
    ProductSearchResults search(ProductSearchCriteria criteria);

    /** Autocomplete: product-name completions for a typed prefix. */
    List<String> suggest(String prefix, int limit);
}
