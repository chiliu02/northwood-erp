package com.northwood.graphqlbff.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GraphQL {@code PlaceOrderInput} — bound from the {@code placeOrder} mutation
 * argument and serialised verbatim as the POST body to sales-service's
 * {@code PlaceOrderCommand} (component names line up 1:1 with the command's
 * JSON fields, so no translation layer is needed).
 */
public record PlaceOrderInput(
    String orderNumber,
    String customerCode,
    String currencyCode,
    LocalDate requestedDeliveryDate,
    String paymentTerms,
    BigDecimal depositPercent,
    List<PlaceOrderLineInput> lines
) {}
