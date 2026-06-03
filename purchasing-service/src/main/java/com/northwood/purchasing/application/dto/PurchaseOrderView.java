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
    String purchaseRequisitionNumber,
    String currencyCode,
    BigDecimal subtotalAmount,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String status,
    long version,
    List<PurchaseOrderLineView> lines
) {
    /**
     * @param purchaseRequisitionNumber the originating requisition's
     *        human-readable number, looked up by the application service (the PO
     *        aggregate stores only the requisition's id). May be {@code null} if
     *        the requisition can't be resolved.
     */
    public static PurchaseOrderView from(PurchaseOrder po, String purchaseRequisitionNumber) {
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
            purchaseRequisitionNumber,
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
