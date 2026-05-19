package com.northwood.finance.application.dto;

import com.northwood.finance.domain.SupplierInvoice;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link SupplierInvoice} for the wire layer. */
public record SupplierInvoiceView(
    UUID id,
    String internalInvoiceNumber,
    String supplierInvoiceNumber,
    UUID purchaseOrderHeaderId,
    UUID goodsReceiptHeaderId,
    UUID supplierId,
    String supplierName,
    String currencyCode,
    BigDecimal subtotalAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String status,
    String matchStatus,
    long version,
    List<SupplierInvoiceLineView> lines
) {
    public static SupplierInvoiceView from(SupplierInvoice si) {
        return new SupplierInvoiceView(
            si.id().value(),
            si.internalInvoiceNumber(), si.supplierInvoiceNumber(),
            si.purchaseOrderHeaderId(), si.goodsReceiptHeaderId(),
            si.supplierId(), si.supplierName(),
            si.currencyCode(),
            si.subtotalAmount(), si.taxAmount(), si.totalAmount(),
            si.status().dbValue(), si.matchStatus().dbValue(),
            si.version(),
            si.lines().stream().map(SupplierInvoiceLineView::from).toList()
        );
    }
}
