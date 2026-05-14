package com.northwood.finance.application.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecordCustomerPaymentCommand(
    @NotBlank @Size(max = 50) String paymentNumber,
    @NotNull UUID customerInvoiceHeaderId,
    @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal amount,
    @NotBlank @Pattern(regexp = "bank_transfer|cash|card|cheque") String paymentMethod,
    LocalDate paymentDate
) {}
