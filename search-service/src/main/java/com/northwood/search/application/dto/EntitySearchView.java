package com.northwood.search.application.dto;

/**
 * One hit from the global cross-service search — a single entity of any type
 * ({@code entityType} = product / customer / sales_order / supplier /
 * purchase_order / customer_invoice), with its display {@code title}, exact
 * {@code code}, {@code subtitle}, {@code status}, and the relevance {@code score}.
 */
public record EntitySearchView(
    String entityType,
    String entityId,
    String title,
    String code,
    String subtitle,
    String status,
    double score
) {}
