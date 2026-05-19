package com.northwood.purchasing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Value object: one line on a purchase requisition. Phase 1 doesn't track
 * conversion-to-PO state on the line; that arrives in phase 2.
 */
public record PurchaseRequisitionLine(
    UUID id,
    int lineNumber,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal requestedQuantity,
    LocalDate requiredDate,
    UUID suggestedSupplierId,
    String suggestedSupplierName,
    PurchaseRequisition.LineStatus status
) {}
