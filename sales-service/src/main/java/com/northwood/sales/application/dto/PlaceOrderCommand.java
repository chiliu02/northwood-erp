package com.northwood.sales.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PlaceOrderCommand(
    @NotBlank @Size(max = 50) String orderNumber,
    @NotBlank @Size(max = 50) String customerCode,
    LocalDate requestedDeliveryDate,
    @NotBlank @Size(min = 3, max = 3) String currencyCode,
    /**
     * Optional per-order override of the customer's
     * {@code defaultPaymentTerms}. Null = inherit from customer. One of
     * {@code "on_shipment"} / {@code "prepayment"}; validated against
     * {@code PaymentTerms.fromDb} in {@code SalesOrderService.placeOrder}.
     */
    String paymentTerms,
    @NotEmpty @Valid List<OrderLine> lines
) {
    public record OrderLine(
        @NotNull UUID productId,
        @NotBlank String productSku,
        @NotBlank String productName,
        @NotNull BigDecimal orderedQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate
    ) {}
}
