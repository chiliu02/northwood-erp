package com.northwood.finance.application.dto;

import com.northwood.finance.domain.CustomerInvoiceLine;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link CustomerInvoiceLine} for the wire layer. */
public record CustomerInvoiceLineView(
    UUID lineId,
    int lineNumber,
    UUID salesOrderLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal lineTotal
) {
    public static CustomerInvoiceLineView from(CustomerInvoiceLine l) {
        return new CustomerInvoiceLineView(
            l.id(), l.lineNumber(), l.salesOrderLineId(),
            l.productId(), l.productSku(), l.productName(),
            l.quantity(), l.unitPrice(),
            l.taxRate(), l.taxAmount(), l.lineTotal()
        );
    }
}
