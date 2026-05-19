package com.northwood.finance.application.dto;

import com.northwood.finance.domain.CustomerInvoice;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link CustomerInvoice} for the wire layer. */
public record CustomerInvoiceView(
    UUID id,
    String invoiceNumber,
    UUID salesOrderHeaderId,
    UUID customerId,
    String customerCode,
    String customerName,
    String currencyCode,
    BigDecimal subtotalAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String status,
    long version,
    List<CustomerInvoiceLineView> lines
) {
    public static CustomerInvoiceView from(CustomerInvoice ci) {
        return new CustomerInvoiceView(
            ci.id().value(),
            ci.invoiceNumber(),
            ci.salesOrderHeaderId(),
            ci.customerId(),
            ci.customerCode(),
            ci.customerName(),
            ci.currencyCode(),
            ci.subtotalAmount(),
            ci.taxAmount(),
            ci.totalAmount(),
            ci.status().dbValue(),
            ci.version(),
            ci.lines().stream().map(CustomerInvoiceLineView::from).toList()
        );
    }
}
