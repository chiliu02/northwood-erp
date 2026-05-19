package com.northwood.purchasing.application.dto;

import com.northwood.purchasing.domain.PurchaseOrder;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link PurchaseOrder} for the wire layer. */
public record PurchaseOrderView(
    UUID id,
    String purchaseOrderNumber,
    UUID supplierId,
    String supplierCode,
    String supplierName,
    UUID purchaseRequisitionHeaderId,
    String currencyCode,
    BigDecimal subtotalAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String status,
    long version,
    List<PurchaseOrderLineView> lines
) {
    public static PurchaseOrderView from(PurchaseOrder po) {
        List<PurchaseOrderLineView> lineViews = po.lines().stream()
            .map(PurchaseOrderLineView::from)
            .toList();
        return new PurchaseOrderView(
            po.id().value(),
            po.purchaseOrderNumber(),
            po.supplierId(),
            po.supplierCode(),
            po.supplierName(),
            po.purchaseRequisitionHeaderId(),
            po.currencyCode(),
            po.subtotalAmount(),
            po.taxAmount(),
            po.totalAmount(),
            po.status().dbValue(),
            po.version(),
            lineViews
        );
    }
}
