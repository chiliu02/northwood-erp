package com.northwood.finance.application.dto;

import com.northwood.finance.domain.Payment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link Payment} for the wire layer. */
public record PaymentView(
    UUID id,
    String paymentNumber,
    String paymentDirection,
    String paymentType,
    UUID supplierId,
    String partyName,
    LocalDate paymentDate,
    String paymentMethod,
    String currencyCode,
    BigDecimal amount,
    String status,
    long version,
    List<PaymentAllocationView> allocations
) {
    public static PaymentView from(Payment p) {
        return new PaymentView(
            p.id().value(),
            p.paymentNumber(),
            p.paymentDirection().dbValue(),
            p.paymentType().dbValue(),
            p.supplierId(),
            p.partyName(),
            p.paymentDate(),
            p.paymentMethod().dbValue(),
            p.currencyCode(),
            p.amount(),
            p.status().dbValue(),
            p.version(),
            p.allocations().stream().map(PaymentAllocationView::from).toList()
        );
    }
}
