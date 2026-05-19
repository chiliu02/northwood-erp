package com.northwood.inventory.application.dto;

import com.northwood.inventory.domain.GoodsReceipt;
import java.util.List;
import java.util.UUID;

/** Read-side projection of {@link GoodsReceipt} for the wire layer. */
public record GoodsReceiptView(
    UUID id,
    String goodsReceiptNumber,
    UUID purchaseOrderHeaderId,
    UUID supplierId,
    String supplierName,
    UUID warehouseId,
    String warehouseCode,
    String status,
    List<GoodsReceiptLineView> lines,
    long version
) {
    public static GoodsReceiptView from(GoodsReceipt gr) {
        List<GoodsReceiptLineView> lineViews = gr.lines().stream()
            .map(GoodsReceiptLineView::from)
            .toList();
        return new GoodsReceiptView(
            gr.id().value(), gr.goodsReceiptNumber(),
            gr.purchaseOrderHeaderId(), gr.supplierId(), gr.supplierName(),
            gr.warehouseId(), gr.warehouseCode(),
            gr.status().dbValue(), lineViews, gr.version()
        );
    }
}
