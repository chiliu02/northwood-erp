package com.northwood.search.application.dto;

import java.util.List;

/**
 * A global cross-service search response: the relevance-ranked {@code hits}
 * across every bounded context, the total match count, and an {@code entityType}
 * terms facet (how many hits of each type) for a "filter by type" UI.
 */
public record GlobalSearchResults(
    long totalHits,
    List<EntitySearchView> hits,
    List<FacetBucket> entityTypeFacets
) {}
