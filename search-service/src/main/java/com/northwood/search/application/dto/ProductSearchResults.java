package com.northwood.search.application.dto;

import java.util.List;

/**
 * A product search response: the relevance-ranked {@code hits}, the total match
 * count, and the {@code productType} / {@code status} terms facets (bucket
 * counts computed over the full filtered result set, for a faceted filter UI).
 */
public record ProductSearchResults(
    long totalHits,
    List<ProductSearchView> hits,
    List<FacetBucket> productTypeFacets,
    List<FacetBucket> statusFacets
) {}
