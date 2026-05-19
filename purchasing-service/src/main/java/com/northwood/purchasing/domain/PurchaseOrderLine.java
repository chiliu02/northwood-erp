package com.northwood.purchasing.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Value object: one line on a purchase order. {@code lineTotal} is
 * {@code orderedQuantity * unitPrice} computed at creation; the schema
 * enforces non-negative invariants. Conversion-from-PR populates
 * {@code purchaseRequisitionLineId} so the round-trip is auditable.
 */
public record PurchaseOrderLine(
    UUID id,
    int lineNumber,
    UUID purchaseRequisitionLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal orderedQuantity,
    BigDecimal unitPrice,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal lineTotal,
    PurchaseOrder.LineStatus status
) {}
