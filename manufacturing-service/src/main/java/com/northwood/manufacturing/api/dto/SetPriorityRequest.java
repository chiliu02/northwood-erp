package com.northwood.manufacturing.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetPriorityRequest(
    @NotBlank String priority,    // 'low' | 'normal' | 'high' | 'urgent'
    @Size(max = 500) String reason
) {}
