package com.northwood.manufacturing.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SkipOperationRequest(@NotBlank String reason) {}
