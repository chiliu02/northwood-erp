package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.inventory.domain.events.GoodsReceived.ReceivedLine;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Goods-receipt lifecycle status. Mirrors the schema CHECK on
     * {@code inventory.goods_receipt_header.status}. Today's Java only ever
     * writes {@code POSTED}; {@code DRAFT} is the schema default for
     * hand-inserted rows, and {@code REVERSED} is forward-prep for a future
     * data-entry-correction flow (counter-stock-movement). See the §2.0
     * design discussion captured 2026-05-19 — `cancelled` was renamed to
     * `reversed` in the schema CHECK migration to match the accounting
     * semantics (you can't cancel a physical receipt; you can only reverse
     * it with a counter-entry).
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java. */
        DRAFT("draft"),
        POSTED("posted"),
        /** Schema-prep — not currently produced by Java. */
        REVERSED("reversed");

        private final String dbValue;

        Status(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static Status fromDb(String value) {
            for (Status s : values()) {
                if (s.dbValue.equals(value)) return s;
            }
            throw Assert.unknownValue("goods_receipt status", value);
        }
    }

    private final GoodsReceiptId id;
    private final String goodsReceiptNumber;
    private final UUID purchaseOrderHeaderId;
    private final UUID supplierId;
    private final String supplierName;
    private final UUID warehouseId;
    private final String warehouseCode;
    private final Status status;
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
        Assert.notNull(purchaseOrderHeaderId, "purchaseOrderHeaderId");
        Assert.notNull(warehouseId, "warehouseId");
        Assert.notEmpty(lines, "at least one line is required");
        GoodsReceiptId id = GoodsReceiptId.newId();
        GoodsReceipt gr = new GoodsReceipt(
            id, goodsReceiptNumber, purchaseOrderHeaderId,
            supplierId, supplierName,
            warehouseId, warehouseCode,
            Status.POSTED, new ArrayList<>(lines), 0L
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
        Status status, List<GoodsReceiptLine> lines, long version
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
        Status status, List<GoodsReceiptLine> lines, long version
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
    public Status status()                  { return status; }
    public List<GoodsReceiptLine> lines()   { return List.copyOf(lines); }
    public long version()                   { return version; }
}
