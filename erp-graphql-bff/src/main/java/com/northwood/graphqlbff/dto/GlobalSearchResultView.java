package com.northwood.graphqlbff.dto;

import java.util.List;

/**
 * Mirrors search-service's {@code GlobalSearchResults} (read model B, the global
 * cross-service search at {@code GET /api/search}). Maps 1:1 to the GraphQL
 * {@code GlobalSearchResult} type.
 */
public record GlobalSearchResultView(
    long totalHits,
    List<EntityHitView> hits,
    List<FacetBucketView> entityTypeFacets
) {}
