package com.northwood.manufacturing.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateBomDraftRequest(
    @NotNull UUID finishedProductId,
    @NotBlank @Size(max = 50) String finishedProductSku,
    @NotBlank @Size(max = 200) String finishedProductName,
    @Size(max = 20) String version
) {}
