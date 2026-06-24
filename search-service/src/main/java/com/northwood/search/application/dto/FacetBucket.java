package com.northwood.search.application.dto;

/**
 * One bucket of a terms facet: a field value and how many matching documents
 * carry it (e.g. {@code {key: "finished_good", count: 12}}). Drives filter UIs
 * that show counts per option.
 */
public record FacetBucket(String key, long count) {}
