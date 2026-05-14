package com.northwood.purchasing.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ApprovePurchaseOrderRequest(
    @NotBlank String approver,
    String reason
) {}
