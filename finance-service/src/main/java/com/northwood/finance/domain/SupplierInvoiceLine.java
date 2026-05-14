package com.northwood.finance.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Value object: one line on a supplier invoice. Carries back-references to
 * the originating PO + GR lines so 3-way match can compare quantities (and
 * later, prices) without cross-schema queries.
 */
public record SupplierInvoiceLine(
    UUID id,
    int lineNumber,
    UUID purchaseOrderLineId,
    UUID goodsReceiptLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal lineTotal
) {}
