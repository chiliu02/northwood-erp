package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationLine;
import com.northwood.inventory.domain.StockReservationRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
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
 */
public final class InMemoryStockReservationRepository implements StockReservationRepository {

    private final Map<UUID, StockReservation> byHeaderId = new HashMap<>();
    private final Map<UUID, StockReservation.Status> statusByHeaderId = new HashMap<>();
    private final Map<UUID, UUID> warehouseByHeaderId = new HashMap<>();
    private final Map<UUID, List<ReservedLineSnapshot>> linesByHeaderId = new HashMap<>();

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

        List<ReservedLineSnapshot> snapshots = new ArrayList<>();
        for (StockReservationLine line : reservation.lines()) {
            if (line.reservedQuantity() != null && line.reservedQuantity().signum() > 0) {
                snapshots.add(new ReservedLineSnapshot(line.productId(), line.reservedQuantity()));
            }
        }
        linesByHeaderId.put(headerId, snapshots);

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
        return new ArrayList<>(linesByHeaderId.getOrDefault(stockReservationHeaderId, List.of()));
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
        linesByHeaderId.remove(stockReservationHeaderId);
    }

    private static boolean isActive(StockReservation.Status status) {
        return status == StockReservation.Status.RESERVED || status == StockReservation.Status.PARTIALLY_RESERVED;
    }
}
