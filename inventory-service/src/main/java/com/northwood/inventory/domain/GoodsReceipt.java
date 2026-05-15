package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.inventory.domain.events.GoodsReceived.ReceivedLine;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a goods receipt: header + lines. Phase 3 supports
 * one creation path: {@link #post}, which records all lines as received
 * against a purchase order and goes straight to status {@code 'posted'}.
 * The {@code 'draft'} status (a clerk staging a partial receipt over time)
 * is reserved for a future slice.
 *
 * <p>The aggregate emits {@link GoodsReceived} for cross-context
 * consumption (purchasing matches PO lines; manufacturing un-parks shortage
 * sagas).
 */
public final class GoodsReceipt {

    /**
     * Wire-format aggregate-type stamped onto {@code inventory.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = InventoryAggregateTypes.GOODS_RECEIPT;

    /** Status — wire-format string stored in inventory.goods_receipt_header.status. */
    public static final String POSTED = "posted";


    private final GoodsReceiptId id;
    private final String goodsReceiptNumber;
    private final UUID purchaseOrderHeaderId;
    private final UUID supplierId;
    private final String supplierName;
    private final UUID warehouseId;
    private final String warehouseCode;
    private final String status;
    private final List<GoodsReceiptLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: post a goods receipt against a PO. Goes straight to 'posted'. */
    public static GoodsReceipt post(
        String goodsReceiptNumber,
        UUID purchaseOrderHeaderId,
        UUID supplierId,
        String supplierName,
        UUID warehouseId,
        String warehouseCode,
        List<GoodsReceiptLine> lines
    ) {
        Objects.requireNonNull(purchaseOrderHeaderId, "purchaseOrderHeaderId");
        Objects.requireNonNull(warehouseId, "warehouseId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        GoodsReceiptId id = GoodsReceiptId.newId();
        GoodsReceipt gr = new GoodsReceipt(
            id, goodsReceiptNumber, purchaseOrderHeaderId,
            supplierId, supplierName,
            warehouseId, warehouseCode,
            "posted", new ArrayList<>(lines), 0L
        );

        List<ReceivedLine> wireLines = new ArrayList<>();
        for (GoodsReceiptLine l : lines) {
            wireLines.add(new ReceivedLine(
                l.id(), l.purchaseOrderLineId(),
                l.productId(), l.productSku(), l.productName(),
                l.receivedQuantity(), l.unitCost()
            ));
        }
        gr.pendingEvents.add(new GoodsReceived(
            UUID.randomUUID(),
            id.value(),
            goodsReceiptNumber,
            purchaseOrderHeaderId,
            warehouseId,
            warehouseCode,
            wireLines,
            Instant.now()
        ));
        return gr;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static GoodsReceipt reconstitute(
        GoodsReceiptId id, String goodsReceiptNumber,
        UUID purchaseOrderHeaderId, UUID supplierId, String supplierName,
        UUID warehouseId, String warehouseCode,
        String status, List<GoodsReceiptLine> lines, long version
    ) {
        return new GoodsReceipt(
            id, goodsReceiptNumber, purchaseOrderHeaderId,
            supplierId, supplierName,
            warehouseId, warehouseCode,
            status, new ArrayList<>(lines), version
        );
    }

    private GoodsReceipt(
        GoodsReceiptId id, String goodsReceiptNumber,
        UUID purchaseOrderHeaderId, UUID supplierId, String supplierName,
        UUID warehouseId, String warehouseCode,
        String status, List<GoodsReceiptLine> lines, long version
    ) {
        this.id = id;
        this.goodsReceiptNumber = goodsReceiptNumber;
        this.purchaseOrderHeaderId = purchaseOrderHeaderId;
        this.supplierId = supplierId;
        this.supplierName = supplierName;
        this.warehouseId = warehouseId;
        this.warehouseCode = warehouseCode;
        this.status = status;
        this.lines = lines;
        this.version = version;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public GoodsReceiptId id()              { return id; }
    public String goodsReceiptNumber()      { return goodsReceiptNumber; }
    public UUID purchaseOrderHeaderId()     { return purchaseOrderHeaderId; }
    public UUID supplierId()                { return supplierId; }
    public String supplierName()            { return supplierName; }
    public UUID warehouseId()               { return warehouseId; }
    public String warehouseCode()           { return warehouseCode; }
    public String status()                  { return status; }
    public List<GoodsReceiptLine> lines()   { return List.copyOf(lines); }
    public long version()                   { return version; }
}
