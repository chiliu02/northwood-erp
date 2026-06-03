package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.ShipmentPosted.ShippedLine;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for an outbound shipment: header + lines. Phase 5c
 * supports one creation path: {@link #post}, which records all lines as
 * shipped against a sales order and goes straight to status {@code 'posted'}.
 *
 * <p>Phase 5c does not adjust {@code stock_balance} on shipment — the
 * make-to-order flow doesn't currently bump FG inventory on manufacturing
 * completion, so a strict decrement would yield negative balances. Capture
 * the gap as a follow-up: when manufacturing's "production confirmation"
 * event lands (separate slice), wire the shipment-side decrement at the
 * same time. The shipment record + emitted event are what drive the saga.
 */
public final class Shipment {

    /**
     * Wire-format aggregate-type stamped onto {@code inventory.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = InventoryAggregateTypes.SHIPMENT;

    /**
     * Shipment lifecycle status. Mirrors the schema CHECK on
     * {@code inventory.shipment_header.status}. Today's Java only ever writes
     * {@code POSTED}; {@code DRAFT} is the schema default for hand-inserted
     * rows, and {@code REVERSED} is forward-prep for a future
     * data-entry-correction flow (counter-stock-movement). Design discussion
     * 2026-05-19: `cancelled` was renamed to `reversed` in the schema CHECK
     * migration to match the accounting semantics (you can't cancel a physical
     * shipment once posted; you can only reverse it with a counter-entry).
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
            throw Assert.unknownValue("shipment status", value);
        }
    }

    private final ShipmentId id;
    private final String shipmentNumber;
    private final UUID salesOrderHeaderId;
    private final String salesOrderNumber;
    private final UUID customerId;
    private final String customerName;
    private final UUID warehouseId;
    private final String warehouseCode;
    private final Status status;
    private final List<ShipmentLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: post a shipment against a sales order. Goes straight to 'posted'. */
    public static Shipment post(
        String shipmentNumber,
        UUID salesOrderHeaderId,
        String salesOrderNumber,
        UUID customerId,
        String customerName,
        UUID warehouseId,
        String warehouseCode,
        List<ShipmentLine> lines
    ) {
        Assert.notNull(salesOrderHeaderId, "salesOrderHeaderId");
        Assert.notNull(warehouseId, "warehouseId");
        Assert.notEmpty(lines, "at least one line is required");
        ShipmentId id = ShipmentId.newId();
        Shipment s = new Shipment(
            id, shipmentNumber, salesOrderHeaderId, salesOrderNumber,
            customerId, customerName,
            warehouseId, warehouseCode,
            Status.POSTED, new ArrayList<>(lines), 0L
        );

        List<ShippedLine> wireLines = new ArrayList<>();
        for (ShipmentLine l : lines) {
            wireLines.add(new ShippedLine(
                l.id(), l.salesOrderLineId(),
                l.productId(), l.productSku(), l.productName(),
                l.shippedQuantity(), l.unitCost()
            ));
        }
        s.pendingEvents.add(new ShipmentPosted(
            UUID.randomUUID(),
            id.value(),
            shipmentNumber,
            salesOrderHeaderId,
            customerId,
            customerName,
            warehouseId,
            warehouseCode,
            wireLines,
            Instant.now()
        ));
        return s;
    }

    /** Factory: hydrate from the DB; emits no events. */
    public static Shipment reconstitute(
        ShipmentId id, String shipmentNumber, UUID salesOrderHeaderId, String salesOrderNumber,
        UUID customerId, String customerName,
        UUID warehouseId, String warehouseCode,
        Status status, List<ShipmentLine> lines, long version
    ) {
        return new Shipment(
            id, shipmentNumber, salesOrderHeaderId, salesOrderNumber,
            customerId, customerName,
            warehouseId, warehouseCode,
            status, new ArrayList<>(lines), version
        );
    }

    private Shipment(
        ShipmentId id, String shipmentNumber, UUID salesOrderHeaderId, String salesOrderNumber,
        UUID customerId, String customerName,
        UUID warehouseId, String warehouseCode,
        Status status, List<ShipmentLine> lines, long version
    ) {
        this.id = id;
        this.shipmentNumber = shipmentNumber;
        this.salesOrderHeaderId = salesOrderHeaderId;
        this.salesOrderNumber = salesOrderNumber;
        this.customerId = customerId;
        this.customerName = customerName;
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

    public ShipmentId id()                       { return id; }
    public String shipmentNumber()                { return shipmentNumber; }
    public UUID salesOrderHeaderId()              { return salesOrderHeaderId; }
    public String salesOrderNumber()              { return salesOrderNumber; }
    public UUID customerId()                      { return customerId; }
    public String customerName()                  { return customerName; }
    public UUID warehouseId()                     { return warehouseId; }
    public String warehouseCode()                 { return warehouseCode; }
    public Status status()                        { return status; }
    public List<ShipmentLine> lines()             { return List.copyOf(lines); }
    public long version()                         { return version; }
}
