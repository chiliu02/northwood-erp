package com.northwood.finance.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RecordCustomerPaymentMultiCommand(
    @NotBlank @Size(max = 50) String paymentNumber,
    @NotBlank @Pattern(regexp = "bank_transfer|cash|card|cheque") String paymentMethod,
    LocalDate paymentDate,
    @NotEmpty @Valid List<InvoiceLine> invoices
) {
    public record InvoiceLine(
        @NotNull UUID customerInvoiceHeaderId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal amount
    ) {}
}
