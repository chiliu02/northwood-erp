package com.northwood.graphqlbff.dto;

/**
 * One global-search hit (any bounded context). Mirrors search-service's
 * {@code EntitySearchView}; maps 1:1 to the GraphQL {@code EntityHit} type.
 */
public record EntityHitView(
    String entityType,
    String entityId,
    String title,
    String code,
    String subtitle,
    String status,
    double score
) {}
