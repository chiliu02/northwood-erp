package com.northwood.purchasing.application.dto;

import com.northwood.purchasing.domain.PurchaseOrderLine;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link PurchaseOrderLine} for the wire layer. */
public record PurchaseOrderLineView(
    UUID lineId,
    int lineNumber,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal orderedQuantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String status
) {
    public static PurchaseOrderLineView from(PurchaseOrderLine l) {
        return new PurchaseOrderLineView(
            l.id(), l.lineNumber(),
            l.productId(), l.productSku(), l.productName(),
            l.orderedQuantity(), l.unitPrice(),
            l.lineTotal(), l.status().code()
        );
    }
}
