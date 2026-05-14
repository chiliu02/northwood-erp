package com.northwood.finance.application.dto;

import com.northwood.finance.domain.PaymentAllocation;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link PaymentAllocation} for the wire layer. */
public record PaymentAllocationView(
    UUID allocationId,
    UUID supplierInvoiceHeaderId,
    BigDecimal allocatedAmount,
    String status
) {
    public static PaymentAllocationView from(PaymentAllocation a) {
        return new PaymentAllocationView(
            a.id(), a.supplierInvoiceHeaderId(), a.allocatedAmount(), a.status()
        );
    }
}
