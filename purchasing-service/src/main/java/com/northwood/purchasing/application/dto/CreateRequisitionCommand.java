package com.northwood.purchasing.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateRequisitionCommand(
    @NotBlank @Size(max = 50) String requisitionNumber,
    @Size(max = 100) String requestedBy,
    @NotEmpty @Valid List<RequisitionLineRequest> lines
) {}
