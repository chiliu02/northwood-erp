package com.northwood.inventory.domain.events;

import com.northwood.shared.domain.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Goods receipt has been posted against a purchase order. Carries the
 * received-quantity-per-line so consumers can act:
 *
 * <ul>
 *   <li>Purchasing matches receipts against PO lines (bumps
 *       {@code received_quantity}, advances P2P saga).</li>
 *   <li>Manufacturing un-parks any work-order saga in
 *       {@code raw_material_shortage} whose work-order materials include the
 *       received product.</li>
 * </ul>
 *
 * <p>Stock balance updates happen inside inventory's own transaction at
 * receipt time and do not need a separate event — the existing per-product
 * partition key on the bus preserves order.
 */
public record GoodsReceived(
    UUID eventId,
    UUID aggregateId,
    String goodsReceiptNumber,
    UUID purchaseOrderHeaderId,
    UUID warehouseId,
    String warehouseCode,
    List<ReceivedLine> lines,
    Instant occurredAt
) implements DomainEvent {

    public static final String EVENT_TYPE = "inventory.GoodsReceived";

    @Override public String eventType() { return EVENT_TYPE; }

    public record ReceivedLine(
        UUID receiptLineId,
        UUID purchaseOrderLineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal receivedQuantity,
        BigDecimal unitCost
    ) {}
}
