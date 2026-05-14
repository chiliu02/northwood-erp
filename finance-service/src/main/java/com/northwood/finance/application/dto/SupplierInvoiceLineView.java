package com.northwood.finance.application.dto;

import com.northwood.finance.domain.SupplierInvoiceLine;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link SupplierInvoiceLine} for the wire layer. */
public record SupplierInvoiceLineView(
    UUID lineId,
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
) {
    public static SupplierInvoiceLineView from(SupplierInvoiceLine l) {
        return new SupplierInvoiceLineView(
            l.id(), l.lineNumber(),
            l.purchaseOrderLineId(), l.goodsReceiptLineId(),
            l.productId(), l.productSku(), l.productName(),
            l.quantity(), l.unitPrice(),
            l.taxRate(), l.taxAmount(), l.lineTotal()
        );
    }
}
