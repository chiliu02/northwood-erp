package com.northwood.search.application.dto;

import java.math.BigDecimal;

/**
 * Inputs to a product catalog search. {@code q} is the free-text query (fuzzy,
 * multi-field); the rest are optional filters/facets. {@code null} fields are
 * not applied. {@code page}/{@code size} paginate the ranked hits.
 */
public record ProductSearchCriteria(
    String q,
    String productType,
    String status,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    int page,
    int size
) {}
