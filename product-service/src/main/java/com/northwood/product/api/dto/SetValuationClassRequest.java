package com.northwood.product.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetValuationClassRequest(
    @NotBlank @Size(max = 50) String valuationClass
) {}
