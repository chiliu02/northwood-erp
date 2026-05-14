package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.inventory.domain.events.ShipmentPosted.ShippedLine;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    /** Status — wire-format string stored in inventory.shipment_header.status. */
    public static final String POSTED = "posted";


    private final ShipmentId id;
    private final String shipmentNumber;
    private final UUID salesOrderHeaderId;
    private final UUID customerId;
    private final String customerName;
    private final UUID warehouseId;
    private final String warehouseCode;
    private final String status;
    private final List<ShipmentLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Factory: post a shipment against a sales order. Goes straight to 'posted'. */
    public static Shipment post(
        String shipmentNumber,
        UUID salesOrderHeaderId,
        UUID customerId,
        String customerName,
        UUID warehouseId,
        String warehouseCode,
        List<ShipmentLine> lines
    ) {
        Objects.requireNonNull(salesOrderHeaderId, "salesOrderHeaderId");
        Objects.requireNonNull(warehouseId, "warehouseId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        ShipmentId id = ShipmentId.newId();
        Shipment s = new Shipment(
            id, shipmentNumber, salesOrderHeaderId,
            customerId, customerName,
            warehouseId, warehouseCode,
            "posted", new ArrayList<>(lines), 0L
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
        ShipmentId id, String shipmentNumber, UUID salesOrderHeaderId,
        UUID customerId, String customerName,
        UUID warehouseId, String warehouseCode,
        String status, List<ShipmentLine> lines, long version
    ) {
        return new Shipment(
            id, shipmentNumber, salesOrderHeaderId,
            customerId, customerName,
            warehouseId, warehouseCode,
            status, new ArrayList<>(lines), version
        );
    }

    private Shipment(
        ShipmentId id, String shipmentNumber, UUID salesOrderHeaderId,
        UUID customerId, String customerName,
        UUID warehouseId, String warehouseCode,
        String status, List<ShipmentLine> lines, long version
    ) {
        this.id = id;
        this.shipmentNumber = shipmentNumber;
        this.salesOrderHeaderId = salesOrderHeaderId;
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
    public UUID customerId()                      { return customerId; }
    public String customerName()                  { return customerName; }
    public UUID warehouseId()                     { return warehouseId; }
    public String warehouseCode()                 { return warehouseCode; }
    public String status()                        { return status; }
    public List<ShipmentLine> lines()             { return List.copyOf(lines); }
    public long version()                         { return version; }
}
