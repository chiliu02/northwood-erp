package com.northwood.search.application;

import com.northwood.search.application.dto.GlobalSearchResults;

/**
 * Read side of the {@code entities} index (read model B) — one search box over
 * every bounded context. The search-side successor to the BFF's in-memory audit
 * fan-out: ES does the cross-entity relevance ranking in one query.
 */
public interface GlobalSearchQueryPort {

    /**
     * Full-text fuzzy search across all entity types.
     *
     * @param q          free-text query (fuzzy, multi-field); blank → match all
     * @param entityType optional filter to one {@code EntityDocument.TYPE_*}; null → all types
     */
    GlobalSearchResults search(String q, String entityType, int page, int size);
}
