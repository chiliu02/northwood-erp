package com.northwood.sales.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterCustomerRequest(
    @NotBlank String customerCode,
    @NotBlank String name,
    String email,
    String phone,
    String billingAddress,
    String shippingAddress
) {}
