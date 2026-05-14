package com.northwood.purchasing.application.dto;

import com.northwood.purchasing.domain.PurchaseRequisition;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link PurchaseRequisition} for the wire layer. */
public record PurchaseRequisitionView(
    UUID id,
    String requisitionNumber,
    String sourceType,
    UUID sourceWorkOrderId,
    UUID sourceProductId,
    String status,
    String requestedBy,
    long version,
    List<PurchaseRequisitionLineView> lines
) {
    public static PurchaseRequisitionView from(PurchaseRequisition pr) {
        List<PurchaseRequisitionLineView> lineViews = pr.lines().stream()
            .map(PurchaseRequisitionLineView::from)
            .toList();
        return new PurchaseRequisitionView(
            pr.id().value(),
            pr.requisitionNumber(),
            pr.sourceType(),
            pr.sourceWorkOrderId(),
            pr.sourceProductId(),
            pr.status(),
            pr.requestedBy(),
            pr.version(),
            lineViews
        );
    }
}
