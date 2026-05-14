package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.inventory.domain.events.RawMaterialsReserved.ReservedComponent;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.inventory.domain.events.StockReserved.ReservedLine;
import com.northwood.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a stock reservation against a single sales order or
 * work order. The {@code stock_reservation_header} table CHECK constraint
 * enforces that exactly one source key is set; this aggregate is constructed
 * with the source kind explicit.
 *
 * <p>Reservation lines are pure computational projections of available stock
 * vs. requested quantity at the moment of reservation. The aggregate emits a
 * {@link StockReserved} event ({@code status='reserved'} for full,
 * {@code 'partially_reserved'} otherwise) so the originating saga can advance.
 */
public final class StockReservation {

    // ------------------------------------------------------------
    // Status constants — wire-format strings stored in
    // inventory.stock_reservation_header.status AND carried on
    // {@code inventory.StockReserved} / {@code inventory.RawMaterialsReserved}
    // event payloads. Cross-service consumers (sales/manufacturing saga
    // managers) match these values; they hold their own local copy of the
    // string since cross-module Java imports across services are not allowed.
    // ------------------------------------------------------------
    public static final String RESERVED = "reserved";
    public static final String PARTIALLY_RESERVED = "partially_reserved";
    public static final String FAILED = "failed";

    private final StockReservationId id;
    private final UUID salesOrderId;
    private final UUID workOrderId;
    private final UUID warehouseId;
    private final String status;
    private final List<StockReservationLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Newly-decided reservation against a sales order. Emits StockReserved. */
    public static StockReservation forSalesOrder(
        UUID salesOrderId,
        UUID warehouseId,
        List<StockReservationLine> lines
    ) {
        Objects.requireNonNull(salesOrderId, "salesOrderId");
        Objects.requireNonNull(warehouseId, "warehouseId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        StockReservationId id = StockReservationId.newId();
        boolean anyShort = lines.stream().anyMatch(l -> l.shortageQuantity().signum() > 0);
        boolean nothingReserved = lines.stream().allMatch(l -> l.reservedQuantity().signum() == 0);
        String headerStatus = nothingReserved ? FAILED
            : anyShort ? PARTIALLY_RESERVED
            : RESERVED;
        StockReservation res = new StockReservation(
            id, salesOrderId, null, warehouseId, headerStatus, new ArrayList<>(lines), 0L
        );

        List<ReservedLine> wireLines = new ArrayList<>();
        int lineNumber = 10;
        for (StockReservationLine l : lines) {
            wireLines.add(new ReservedLine(
                lineNumber, l.productId(), l.requestedQuantity(),
                l.reservedQuantity(), l.shortageQuantity(), l.status()
            ));
            lineNumber += 10;
        }
        res.pendingEvents.add(new StockReserved(
            UUID.randomUUID(),
            id.value(),
            salesOrderId,
            id.value(),
            headerStatus,
            wireLines,
            Instant.now()
        ));
        return res;
    }

    /** Newly-decided reservation against a work order. Emits RawMaterialsReserved. */
    public static StockReservation forWorkOrder(
        UUID workOrderId,
        UUID warehouseId,
        List<StockReservationLine> lines
    ) {
        Objects.requireNonNull(workOrderId, "workOrderId");
        Objects.requireNonNull(warehouseId, "warehouseId");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("at least one line is required");
        }
        StockReservationId id = StockReservationId.newId();
        boolean anyShort = lines.stream().anyMatch(l -> l.shortageQuantity().signum() > 0);
        boolean nothingReserved = lines.stream().allMatch(l -> l.reservedQuantity().signum() == 0);
        String headerStatus = nothingReserved ? FAILED
            : anyShort ? PARTIALLY_RESERVED
            : RESERVED;
        StockReservation res = new StockReservation(
            id, null, workOrderId, warehouseId, headerStatus, new ArrayList<>(lines), 0L
        );

        // The line's lineId carries the originating work_order_material_id —
        // see RawMaterialReservationRequestedHandler for the assignment.
        List<ReservedComponent> wire = new ArrayList<>();
        for (StockReservationLine l : lines) {
            wire.add(new ReservedComponent(
                l.lineId(),
                l.productId(),
                l.requestedQuantity(),
                l.reservedQuantity(),
                l.shortageQuantity(),
                l.status()
            ));
        }
        res.pendingEvents.add(new RawMaterialsReserved(
            UUID.randomUUID(),
            id.value(),
            workOrderId,
            id.value(),
            headerStatus,
            wire,
            Instant.now()
        ));
        return res;
    }

    private StockReservation(
        StockReservationId id, UUID salesOrderId, UUID workOrderId, UUID warehouseId,
        String status, List<StockReservationLine> lines, long version
    ) {
        this.id = id;
        this.salesOrderId = salesOrderId;
        this.workOrderId = workOrderId;
        this.warehouseId = warehouseId;
        this.status = status;
        this.lines = lines;
        this.version = version;
    }

    public List<DomainEvent> pullPendingEvents() {
        List<DomainEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public StockReservationId id()             { return id; }
    public UUID salesOrderId()                 { return salesOrderId; }
    public UUID workOrderId()                  { return workOrderId; }
    public UUID warehouseId()                  { return warehouseId; }
    public String status()                     { return status; }
    public List<StockReservationLine> lines()  { return List.copyOf(lines); }
    public long version()                      { return version; }
}
