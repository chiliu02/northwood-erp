package com.northwood.purchasing.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateSupplierDetailsRequest(
    @NotBlank String name,
    String email,
    String phone,
    String address
) {}
