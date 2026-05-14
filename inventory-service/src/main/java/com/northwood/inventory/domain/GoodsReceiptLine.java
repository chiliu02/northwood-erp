package com.northwood.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Value object: one line on a goods receipt. {@code purchaseOrderLineId}
 * carries back to the originating PO line so purchasing can match per-line
 * received quantities.
 */
public record GoodsReceiptLine(
    UUID id,
    UUID purchaseOrderLineId,
    UUID productId,
    String productSku,
    String productName,
    BigDecimal receivedQuantity,
    BigDecimal unitCost,
    BigDecimal lineCost
) {}
