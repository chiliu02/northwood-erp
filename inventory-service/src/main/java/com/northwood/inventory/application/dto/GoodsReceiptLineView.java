package com.northwood.inventory.application.dto;

import com.northwood.inventory.domain.GoodsReceiptLine;
import java.math.BigDecimal;
import java.util.UUID;

/** Read-side projection of {@link GoodsReceiptLine} for the wire layer. */
public record GoodsReceiptLineView(
    UUID lineId,
    UUID purchaseOrderLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal receivedQuantity,
    BigDecimal unitCost,
    BigDecimal lineCost
) {
    public static GoodsReceiptLineView from(GoodsReceiptLine l) {
        return new GoodsReceiptLineView(
            l.id(), l.purchaseOrderLineId(),
            l.productId(), l.productSku(), l.productName(),
            l.receivedQuantity(), l.unitCost(), l.lineCost()
        );
    }
}
