package com.northwood.search.application.dto;

import java.util.List;

/**
 * An audit/event-analytics response: the newest-first {@code hits}, the total
 * count, and {@code terms} facets — events per {@code sourceService},
 * {@code eventType} and {@code aggregateType} (the "events per service / type"
 * aggregations Kibana also charts).
 */
public record AuditSearchResults(
    long totalHits,
    List<AuditEventView> hits,
    List<FacetBucket> serviceFacets,
    List<FacetBucket> eventTypeFacets,
    List<FacetBucket> aggregateTypeFacets
) {}
