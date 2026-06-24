package com.northwood.search.application.dto;

import java.math.BigDecimal;

/**
 * One product search hit — the wire shape returned to the API. {@code score} is
 * the Elasticsearch relevance score (higher = better match), surfaced so a
 * client can show ranking or sort by it.
 */
public record ProductSearchView(
    String productId,
    String sku,
    String name,
    String productType,
    String status,
    BigDecimal salesPrice,
    double score
) {}
