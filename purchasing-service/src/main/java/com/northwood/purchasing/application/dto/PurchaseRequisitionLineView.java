package com.northwood.purchasing.application.dto;

import com.northwood.purchasing.domain.PurchaseRequisitionLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Read-side projection of {@link PurchaseRequisitionLine} for the wire layer. */
public record PurchaseRequisitionLineView(
    UUID lineId,
    int lineNumber,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal requestedQuantity,
    LocalDate requiredDate,
    UUID suggestedSupplierId,
    String suggestedSupplierName,
    String status
) {
    public static PurchaseRequisitionLineView from(PurchaseRequisitionLine l) {
        return new PurchaseRequisitionLineView(
            l.id(), l.lineNumber(),
            l.productId(), l.productSku(), l.productName(),
            l.requestedQuantity(), l.requiredDate(),
            l.suggestedSupplierId(), l.suggestedSupplierName(),
            l.status()
        );
    }
}
