package com.northwood.purchasing.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RejectPurchaseOrderRequest(
    @NotBlank String rejectedBy,
    String reason
) {}
