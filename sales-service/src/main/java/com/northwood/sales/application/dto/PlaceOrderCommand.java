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
     * {@code "on_shipment"} / {@code "prepayment"} / {@code "cash_on_delivery"}; validated
     * against {@code PaymentTerms.fromDb} in {@code SalesOrderService.placeOrder}.
     */
    String paymentTerms,
    /**
     * §2.32: up-front fraction (0,100] for {@code deposit} orders. Null = use
     * the default 50% when {@code paymentTerms = "deposit"}; ignored (must stay
     * null) for every other term. Validated in {@code SalesOrderService.placeOrder}.
     */
    BigDecimal depositPercent,
    @NotEmpty @Valid List<OrderLine> lines
) {
    /**
     * Convenience for the common non-deposit case — no up-front
     * {@code depositPercent} (preserves call sites that predate §2.32).
     */
    public PlaceOrderCommand(
        String orderNumber, String customerCode, LocalDate requestedDeliveryDate,
        String currencyCode, String paymentTerms, List<OrderLine> lines
    ) {
        this(orderNumber, customerCode, requestedDeliveryDate, currencyCode, paymentTerms, null, lines);
    }

    public record OrderLine(
        @NotNull UUID productId,
        @NotBlank String productSku,
        @NotBlank String productName,
        @NotNull BigDecimal orderedQuantity,
        BigDecimal unitPrice,
        BigDecimal taxRate
    ) {}
}
