package com.northwood.finance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ManualReviewRequest(
    @NotBlank @Size(max = 100) String reviewer,
    @Size(max = 500) String reason
) {}
