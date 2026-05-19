package com.northwood.finance.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Value object: one settlement line linking a payment to an invoice. Either
 * {@code customerInvoiceHeaderId} or {@code supplierInvoiceHeaderId} is set;
 * the schema CHECK enforces exactly-one. Phase 5a uses only the supplier
 * side; customer-invoice payments arrive in phase 5c.
 */
public record PaymentAllocation(
    UUID id,
    UUID customerInvoiceHeaderId,
    UUID supplierInvoiceHeaderId,
    BigDecimal allocatedAmount,
    Payment.AllocationStatus status
) {}
