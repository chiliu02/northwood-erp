package com.northwood.graphqlbff.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Mirror of sales-service {@code CustomerView} (GET /api/customers[/{id}]).
 * Backs the GraphQL {@code Customer} type. The upstream JSON names the key
 * {@code customerId}; the GraphQL schema exposes it as {@code id}, so the
 * component is named {@code id} and {@link JsonProperty} maps the wire name.
 */
public record CustomerView(
    @JsonProperty("customerId") UUID id,
    String customerCode,
    String name,
    String email,
    String phone,
    String billingAddress,
    String shippingAddress,
    String status,
    String defaultPaymentTerms,
    long version
) {}
