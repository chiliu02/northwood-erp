package com.northwood.inventory.domain;

import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.inventory.domain.events.RawMaterialsReserved.ReservedComponent;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.inventory.domain.events.StockReserved.ReservedLine;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.domain.LineNumbering;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Wire-format aggregate-type stamped onto {@code inventory.outbox_message.aggregate_type}
     * for events this aggregate emits.
     */
    public static final String AGGREGATE_TYPE = InventoryAggregateTypes.STOCK_RESERVATION;

    /**
     * Stock-reservation status. Mirrors the schema CHECK on
     * {@code inventory.stock_reservation_header.status} (and on
     * {@code inventory.stock_reservation_line.status} — same set, both header
     * and line are read/written from this single enum).
     *
     * <p>Cross-service consumers (sales / manufacturing saga managers) compare
     * the wire-format string from event payloads against their local
     * {@code StockReserved.STATUS_*} / {@code RawMaterialsReserved.STATUS_*}
     * constants — those are the contract surface, not this enum.
     */
    public enum Status {
        /** Schema-prep — not currently produced by Java. */
        PENDING("pending"),
        RESERVED("reserved"),
        PARTIALLY_RESERVED("partially_reserved"),
        FAILED("failed"),
        /** Schema-prep — not currently produced by Java. */
        RELEASED("released"),
        /** Schema-prep — not currently produced by Java. */
        CONSUMED("consumed");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Status fromCode(String value) {
            for (Status s : values()) {
                if (s.code.equals(value)) return s;
            }
            throw Assert.unknownValue("stock_reservation status", value);
        }
    }

    private final StockReservationId id;
    private final UUID salesOrderId;
    private final UUID workOrderId;
    private final UUID warehouseId;
    private final Status status;
    private final List<StockReservationLine> lines;
    private final long version;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    /** Newly-decided reservation against a sales order. Emits StockReserved. */
    public static StockReservation forSalesOrder(
        UUID salesOrderId,
        UUID warehouseId,
        List<StockReservationLine> lines
    ) {
        Assert.notNull(salesOrderId, "salesOrderId");
        Assert.notNull(warehouseId, "warehouseId");
        Assert.notEmpty(lines, "at least one line is required");
        StockReservationId id = StockReservationId.newId();
        boolean anyShort = lines.stream().anyMatch(l -> l.shortageQuantity().signum() > 0);
        boolean nothingReserved = lines.stream().allMatch(l -> l.reservedQuantity().signum() == 0);
        Status headerStatus = nothingReserved ? Status.FAILED
            : anyShort ? Status.PARTIALLY_RESERVED
            : Status.RESERVED;
        StockReservation res = new StockReservation(
            id, salesOrderId, null, warehouseId, headerStatus, new ArrayList<>(lines), 0L
        );

        List<ReservedLine> wireLines = new ArrayList<>();
        int lineNumber = LineNumbering.START;
        for (StockReservationLine l : lines) {
            wireLines.add(new ReservedLine(
                lineNumber, l.productId(), l.requestedQuantity(),
                l.reservedQuantity(), l.shortageQuantity(), l.status().code()
            ));
            lineNumber += LineNumbering.STEP;
        }
        res.pendingEvents.add(new StockReserved(
            UUID.randomUUID(),
            id.value(),
            salesOrderId,
            id.value(),
            headerStatus.code(),
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
        Assert.notNull(workOrderId, "workOrderId");
        Assert.notNull(warehouseId, "warehouseId");
        Assert.notEmpty(lines, "at least one line is required");
        StockReservationId id = StockReservationId.newId();
        boolean anyShort = lines.stream().anyMatch(l -> l.shortageQuantity().signum() > 0);
        boolean nothingReserved = lines.stream().allMatch(l -> l.reservedQuantity().signum() == 0);
        Status headerStatus = nothingReserved ? Status.FAILED
            : anyShort ? Status.PARTIALLY_RESERVED
            : Status.RESERVED;
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
                l.status().code()
            ));
        }
        res.pendingEvents.add(new RawMaterialsReserved(
            UUID.randomUUID(),
            id.value(),
            workOrderId,
            id.value(),
            headerStatus.code(),
            wire,
            Instant.now()
        ));
        return res;
    }

    private StockReservation(
        StockReservationId id, UUID salesOrderId, UUID workOrderId, UUID warehouseId,
        Status status, List<StockReservationLine> lines, long version
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
    public Status status()                     { return status; }
    public List<StockReservationLine> lines()  { return List.copyOf(lines); }
    public long version()                      { return version; }
}
