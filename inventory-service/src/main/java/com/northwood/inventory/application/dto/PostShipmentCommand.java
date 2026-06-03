package com.northwood.inventory.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record PostShipmentCommand(
    @NotBlank @Size(max = 50) String shipmentNumber,
    @NotNull UUID salesOrderHeaderId,
    @Size(max = 50) String salesOrderNumber,
    UUID customerId,
    String customerName,
    @NotBlank String warehouseCode,
    @NotEmpty @Valid List<ShipmentLineRequest> lines
) {}
