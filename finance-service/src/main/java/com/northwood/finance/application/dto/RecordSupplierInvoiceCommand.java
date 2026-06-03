package com.northwood.finance.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RecordSupplierInvoiceCommand(
    @NotBlank @Size(max = 50) String internalInvoiceNumber,
    @NotBlank @Size(max = 50) String supplierInvoiceNumber,
    @NotNull UUID purchaseOrderHeaderId,
    @Size(max = 50) String purchaseOrderNumber,
    UUID goodsReceiptHeaderId,
    @Size(max = 50) String goodsReceiptNumber,
    @NotNull UUID supplierId,
    String supplierCode,
    @NotBlank String supplierName,
    @NotBlank @Size(min = 3, max = 3) String currencyCode,
    @NotEmpty @Valid List<Line> lines
) {
    public record Line(
        @NotNull UUID purchaseOrderLineId,
        UUID goodsReceiptLineId,
        @NotNull UUID productId,
        @NotBlank String productSku,
        @NotBlank String productName,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        BigDecimal taxRate
    ) {}
}
