package com.northwood.sales.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChangeCustomerNameRequest(@NotBlank String name) {}
