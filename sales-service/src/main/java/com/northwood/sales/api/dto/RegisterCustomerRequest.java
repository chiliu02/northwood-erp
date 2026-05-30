package com.northwood.sales.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterCustomerRequest(
    @NotBlank String customerCode,
    @NotBlank String name,
    String email,
    String phone,
    String billingAddress,
    String shippingAddress,
    /**
     * Optional default commercial payment terms. Null = server defaults to
     * {@code on_shipment} (Northwood's credit-terms AR flow). Validated
     * against {@code PaymentTerms.fromDb} in {@code CustomerService}.
     */
    String defaultPaymentTerms
) {}
