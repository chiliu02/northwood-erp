package com.northwood.inventory.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record PostGoodsReceiptCommand(
    @NotBlank @Size(max = 50) String goodsReceiptNumber,
    @NotNull UUID purchaseOrderHeaderId,
    @Size(max = 50) String purchaseOrderNumber,
    UUID supplierId,
    String supplierName,
    @NotBlank String warehouseCode,
    @NotEmpty @Valid List<GoodsReceiptLineRequest> lines
) {}
