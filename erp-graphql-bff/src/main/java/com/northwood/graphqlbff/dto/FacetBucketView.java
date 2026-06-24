package com.northwood.graphqlbff.dto;

/**
 * One terms-facet bucket (field value + match count). Mirrors search-service's
 * {@code FacetBucket}; maps 1:1 to the GraphQL {@code FacetBucket} type.
 */
public record FacetBucketView(String key, long count) {}
