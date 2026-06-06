package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationLine;
import com.northwood.inventory.domain.StockReservationRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory backing for {@link StockReservationRepository}. Drains the
 * aggregate's pending events to the outbox on save, mirroring the Jdbc adapter.
 * Models per-line state (for the §1G line-amendment ops) in {@link Line}.
 */
public final class InMemoryStockReservationRepository implements StockReservationRepository {

    /** Mutable per-line row mirror, so the amendment ops can mutate a single line. */
    private static final class Line {
        final UUID lineId;
        final UUID salesOrderLineId;
        final UUID productId;
        final String productSku;
        final String productName;
        BigDecimal requestedQuantity;
        BigDecimal reservedQuantity;
        BigDecimal shortageQuantity;
        StockReservation.Status status;

        Line(StockReservationLine src) {
            this.lineId = src.lineId();
            this.salesOrderLineId = src.salesOrderLineId();
            this.productId = src.productId();
            this.productSku = src.productSku();
            this.productName = src.productName();
            this.requestedQuantity = src.requestedQuantity();
            this.reservedQuantity = src.reservedQuantity();
            this.shortageQuantity = src.shortageQuantity();
            this.status = src.status();
        }
    }

    private final Map<UUID, StockReservation> byHeaderId = new HashMap<>();
    private final Map<UUID, StockReservation.Status> statusByHeaderId = new HashMap<>();
    private final Map<UUID, UUID> warehouseByHeaderId = new HashMap<>();
    private final Map<UUID, UUID> salesOrderByHeaderId = new HashMap<>();
    private final Map<UUID, List<Line>> linesByHeaderId = new HashMap<>();

    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryStockReservationRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public void save(StockReservation reservation) {
        UUID headerId = reservation.id().value();
        byHeaderId.put(headerId, reservation);
        statusByHeaderId.put(headerId, reservation.status());
        warehouseByHeaderId.put(headerId, reservation.warehouseId());
        if (reservation.salesOrderId() != null) {
            salesOrderByHeaderId.put(headerId, reservation.salesOrderId());
        }

        List<Line> lines = new ArrayList<>();
        for (StockReservationLine line : reservation.lines()) {
            lines.add(new Line(line));
        }
        linesByHeaderId.put(headerId, lines);

        for (DomainEvent event : reservation.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    StockReservation.AGGREGATE_TYPE,
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event),
                    null, null, null, null
                ));
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialise " + event.eventType(), e);
            }
        }
    }

    @Override
    public Optional<StockReservation> findBySalesOrderId(UUID salesOrderId) {
        return byHeaderId.values().stream()
            .filter(r -> salesOrderId.equals(r.salesOrderId()))
            .findFirst();
    }

    @Override
    public Optional<UUID> findActiveHeaderIdForSalesOrder(UUID salesOrderHeaderId) {
        return byHeaderId.values().stream()
            .filter(r -> salesOrderHeaderId.equals(r.salesOrderId()))
            .filter(r -> isActive(statusByHeaderId.get(r.id().value())))
            .map(r -> r.id().value())
            .findFirst();
    }

    @Override
    public Optional<UUID> findAnyHeaderIdForWorkOrder(UUID workOrderId) {
        return byHeaderId.values().stream()
            .filter(r -> workOrderId.equals(r.workOrderId()))
            .map(r -> r.id().value())
            .findFirst();
    }

    @Override
    public Optional<UUID> findAnyHeaderIdForSalesOrder(UUID salesOrderHeaderId) {
        return byHeaderId.values().stream()
            .filter(r -> salesOrderHeaderId.equals(r.salesOrderId()))
            .map(r -> r.id().value())
            .findFirst();
    }

    @Override
    public Optional<UUID> findWarehouseIdForHeader(UUID stockReservationHeaderId) {
        return Optional.ofNullable(warehouseByHeaderId.get(stockReservationHeaderId));
    }

    @Override
    public List<ReservedLineSnapshot> findReservedLines(UUID stockReservationHeaderId) {
        List<ReservedLineSnapshot> out = new ArrayList<>();
        for (Line l : linesByHeaderId.getOrDefault(stockReservationHeaderId, List.of())) {
            if (l.reservedQuantity != null && l.reservedQuantity.signum() > 0) {
                out.add(new ReservedLineSnapshot(l.productId, l.reservedQuantity));
            }
        }
        return out;
    }

    @Override
    public void markReleased(UUID stockReservationHeaderId) {
        statusByHeaderId.put(stockReservationHeaderId, StockReservation.Status.RELEASED);
    }

    @Override
    public void deleteHeaderAndLines(UUID stockReservationHeaderId) {
        byHeaderId.remove(stockReservationHeaderId);
        statusByHeaderId.remove(stockReservationHeaderId);
        warehouseByHeaderId.remove(stockReservationHeaderId);
        salesOrderByHeaderId.remove(stockReservationHeaderId);
        linesByHeaderId.remove(stockReservationHeaderId);
    }

    // ------------------------------------------------------------
    // §1G line amendment
    // ------------------------------------------------------------

    @Override
    public Optional<UUID> findLiveHeaderIdForSalesOrder(UUID salesOrderHeaderId) {
        return byHeaderId.values().stream()
            .filter(r -> salesOrderHeaderId.equals(r.salesOrderId()))
            .filter(r -> statusByHeaderId.get(r.id().value()) != StockReservation.Status.RELEASED)
            .map(r -> r.id().value())
            .findFirst();
    }

    @Override
    public Optional<AmendableSalesOrderLine> findAmendableSalesOrderLine(UUID salesOrderHeaderId, UUID salesOrderLineId) {
        Optional<UUID> headerId = findLiveHeaderIdForSalesOrder(salesOrderHeaderId);
        if (headerId.isEmpty()) {
            return Optional.empty();
        }
        UUID warehouseId = warehouseByHeaderId.get(headerId.get());
        return linesByHeaderId.getOrDefault(headerId.get(), List.of()).stream()
            .filter(l -> salesOrderLineId.equals(l.salesOrderLineId))
            .filter(l -> l.status != StockReservation.Status.RELEASED)
            .map(l -> new AmendableSalesOrderLine(
                headerId.get(), l.lineId, warehouseId, l.productId,
                l.requestedQuantity, l.reservedQuantity, l.status.dbValue()))
            .findFirst();
    }

    @Override
    public void appendLine(UUID stockReservationHeaderId, StockReservationLine line) {
        linesByHeaderId.computeIfAbsent(stockReservationHeaderId, k -> new ArrayList<>()).add(new Line(line));
    }

    @Override
    public void updateLine(
        UUID stockReservationLineId,
        BigDecimal requestedQuantity,
        BigDecimal reservedQuantity,
        BigDecimal shortageQuantity,
        String status
    ) {
        for (List<Line> lines : linesByHeaderId.values()) {
            for (Line l : lines) {
                if (l.lineId.equals(stockReservationLineId)) {
                    l.requestedQuantity = requestedQuantity;
                    l.reservedQuantity = reservedQuantity;
                    l.shortageQuantity = shortageQuantity;
                    l.status = StockReservation.Status.fromDb(status);
                    return;
                }
            }
        }
    }

    @Override
    public void recomputeSalesOrderHeaderStatus(UUID stockReservationHeaderId) {
        List<Line> lines = linesByHeaderId.getOrDefault(stockReservationHeaderId, List.of());
        BigDecimal totalReserved = BigDecimal.ZERO;
        boolean anyShort = false;
        for (Line l : lines) {
            if (l.status == StockReservation.Status.RELEASED) {
                continue;
            }
            totalReserved = totalReserved.add(l.reservedQuantity == null ? BigDecimal.ZERO : l.reservedQuantity);
            if (l.shortageQuantity != null && l.shortageQuantity.signum() > 0) {
                anyShort = true;
            }
        }
        StockReservation.Status status = totalReserved.signum() == 0 ? StockReservation.Status.FAILED
            : anyShort ? StockReservation.Status.PARTIALLY_RESERVED
            : StockReservation.Status.RESERVED;
        statusByHeaderId.put(stockReservationHeaderId, status);
    }

    private static boolean isActive(StockReservation.Status status) {
        return status == StockReservation.Status.RESERVED || status == StockReservation.Status.PARTIALLY_RESERVED;
    }
}
