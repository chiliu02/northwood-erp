package com.northwood.purchasing.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeSupplierStatusRequest(
    @NotBlank String status,   // active | inactive | blocked
    String reason
) {}
