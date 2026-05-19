package com.northwood.sales.application.dto;

import com.northwood.sales.domain.SalesOrderLine;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link SalesOrderLine} for the wire layer. */
public record SalesOrderLineView(
    UUID lineId,
    int lineNumber,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal orderedQuantity,
    BigDecimal reservedQuantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String lineStatus
) {
    public static SalesOrderLineView from(SalesOrderLine l) {
        return new SalesOrderLineView(
            l.lineId(), l.lineNumber(), l.productId(), l.productSku(), l.productName(),
            l.orderedQuantity(), l.reservedQuantity(), l.unitPrice(),
            l.lineTotal(), l.lineStatus().dbValue()
        );
    }
}
