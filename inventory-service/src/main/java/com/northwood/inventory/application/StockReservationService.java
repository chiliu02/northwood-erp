package com.northwood.inventory.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationLine;
import com.northwood.inventory.domain.StockReservationRepository;
import com.northwood.inventory.domain.StockReservationRepository.ReservedLineSnapshot;
import com.northwood.inventory.domain.events.SalesOrderCancellationApplied;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that turns reservation requests into real reservations
 * against {@code inventory.stock_balance}. Handles both flows:
 *
 * <ul>
 *   <li>{@link #reserveForSalesOrder(StockReservationRequested)} — finished-goods
 *       reservation requested by sales (sales-order line). Emits
 *       {@code inventory.StockReserved}.</li>
 *   <li>{@link #reserveForWorkOrder(RawMaterialReservationRequested)}
 *       — raw-materials reservation requested by manufacturing (BOM components
 *       for a work order). Emits {@code inventory.RawMaterialsReserved}.</li>
 * </ul>
 *
 * <p>Both share the per-line reservation logic in {@link #reserveOneLine}: look
 * up {@code stock_balance.available_quantity} via {@link StockBalanceLookup},
 * atomically bump {@code reserved_quantity} via
 * {@link StockBalanceWriter#tryReserveOnHand}, and shape the line as full /
 * partial / failed.
 */
@Service
public class StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(StockReservationService.class);

    private final StockReservationRepository stockReservations;
    private final StockBalanceWriter stockBalances;
    private final StockBalanceLookup balanceLookup;
    private final WarehouseLookup warehouses;
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public StockReservationService(
        StockReservationRepository stockReservations,
        StockBalanceWriter stockBalances,
        StockBalanceLookup balanceLookup,
        WarehouseLookup warehouses,
        OutboxPort outbox,
        ObjectMapper json
    ) {
        this.stockReservations = stockReservations;
        this.stockBalances = stockBalances;
        this.balanceLookup = balanceLookup;
        this.warehouses = warehouses;
        this.outbox = outbox;
        this.json = json;
    }

    @Transactional
    public void reserveForSalesOrder(StockReservationRequested payload) {
        UUID warehouseId = warehouses.findIdByCode(payload.warehouseCode() == null ? "MAIN" : payload.warehouseCode());

        List<StockReservationLine> lines = new ArrayList<>();
        for (StockReservationRequested.RequestedLine req : payload.lines()) {
            lines.add(reserveOneLine(
                warehouseId,
                UUID.randomUUID(),
                req.productId(), req.productSku(), req.productName(),
                req.requestedQuantity()
            ));
        }

        StockReservation reservation = StockReservation.forSalesOrder(
            payload.salesOrderId(), warehouseId, lines
        );
        stockReservations.save(reservation);

        log.info(
            "reserved stock for sales_order={} status={} reservation={} lines={}",
            payload.salesOrderId(), reservation.status(), reservation.id().value(), summary(lines)
        );
    }

    @Transactional
    public void reserveForWorkOrder(RawMaterialReservationRequested payload) {
        UUID warehouseId = warehouses.findIdByCode(payload.warehouseCode() == null ? "MAIN" : payload.warehouseCode());

        // Phase 3 retry support: a make-to-order saga that previously landed
        // at raw_material_shortage will re-emit the reservation request after
        // a goods receipt unblocks it. The schema's UNIQUE on
        // stock_reservation_header.work_order_id blocks a second insert, so
        // we drop any prior reservation for this WO and roll back the
        // reserved_quantity bumps it made before creating the new one.
        cancelPriorReservationFor(payload.workOrderId(), warehouseId);

        List<StockReservationLine> lines = new ArrayList<>();
        for (RawMaterialReservationRequested.RequestedComponent comp : payload.components()) {
            // The line id carries the originating work_order_material_id so the
            // emitted RawMaterialsReserved can be correlated back per component.
            lines.add(reserveOneLine(
                warehouseId,
                comp.workOrderMaterialId(),
                comp.componentProductId(), comp.componentSku(), comp.componentName(),
                comp.requiredQuantity()
            ));
        }

        StockReservation reservation = StockReservation.forWorkOrder(
            payload.workOrderId(), warehouseId, lines
        );
        stockReservations.save(reservation);

        log.info(
            "reserved raw materials for work_order={} status={} reservation={} components={}",
            payload.workOrderId(), reservation.status(), reservation.id().value(), summary(lines)
        );
    }

    /**
     * Release the sales-order reservation tied to this header, decrement the
     * matching {@code stock_balance.reserved_quantity} bumps, and emit
     * {@code inventory.SalesOrderCancellationApplied} with the count released.
     *
     * <p>Idempotent against the absence of a reservation — a cancel that
     * arrives before the stock reservation was even created (or where it was
     * already released) still emits the ack so the sales saga can advance.
     */
    @Transactional
    public void releaseForSalesOrder(UUID salesOrderHeaderId) {
        Optional<UUID> headerId = stockReservations.findActiveHeaderIdForSalesOrder(salesOrderHeaderId);
        int released = 0;

        if (headerId.isPresent()) {
            unwindReservation(headerId.get());
            released = 1;
            log.info("released reservation {} for sales_order={}", headerId.get(), salesOrderHeaderId);
        } else {
            log.info("no live reservation to release for sales_order={}", salesOrderHeaderId);
        }

        SalesOrderCancellationApplied ack = new SalesOrderCancellationApplied(
            UUID.randomUUID(), salesOrderHeaderId, released, Instant.now()
        );
        try {
            outbox.appendPending(OutboxRow.pending(
                ack.eventId(),
                StockReservation.AGGREGATE_TYPE,
                ack.aggregateId(),
                ack.eventType(),
                ack.eventVersion(),
                json.writeValueAsString(ack),
                null, null, null,
                null  // actor: saga-driven; propagation from inbound envelope is a B2 follow-up
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise " + SalesOrderCancellationApplied.EVENT_TYPE, e);
        }
    }

    /**
     * Release the raw-material reservation tied to a cancelled work order.
     * Decrements the matching {@code stock_balance.reserved_quantity} bumps
     * and marks the reservation header status {@code 'released'}. Idempotent
     * against the absence of a reservation (e.g. a WO cancelled before its
     * raw materials were reserved). Doesn't emit an event — manufacturing's
     * cancel-applied ack to the sales saga doesn't depend on this.
     */
    @Transactional
    public void releaseForWorkOrder(UUID workOrderId) {
        Optional<UUID> headerId = stockReservations.findActiveHeaderIdForWorkOrder(workOrderId);
        if (headerId.isEmpty()) {
            log.info("no live raw-material reservation to release for work_order={}", workOrderId);
            return;
        }
        unwindReservation(headerId.get());
        log.info("released raw-material reservation {} for work_order={}", headerId.get(), workOrderId);
    }

    /**
     * Roll back the {@code stock_balance.reserved_quantity} bumps for every
     * line of {@code headerId}, then mark the reservation header
     * {@code 'released'}. Shared subroutine for both release paths.
     */
    private void unwindReservation(UUID headerId) {
        UUID warehouseId = stockReservations.findWarehouseIdForHeader(headerId)
            .orElseThrow(() -> new IllegalStateException(
                "Reservation header " + headerId + " disappeared mid-release"));
        for (ReservedLineSnapshot line : stockReservations.findReservedLines(headerId)) {
            stockBalances.releaseReserved(warehouseId, line.productId(), line.reservedQuantity());
        }
        stockReservations.markReleased(headerId);
    }

    private void cancelPriorReservationFor(UUID workOrderId, UUID warehouseId) {
        Optional<UUID> priorHeaderId = stockReservations.findAnyHeaderIdForWorkOrder(workOrderId);
        if (priorHeaderId.isEmpty()) {
            return;
        }
        UUID id = priorHeaderId.get();
        // Roll back the reserved_quantity bumps from the previous attempt.
        for (ReservedLineSnapshot line : stockReservations.findReservedLines(id)) {
            stockBalances.releaseReserved(warehouseId, line.productId(), line.reservedQuantity());
        }
        // Hard-delete header + lines so the new INSERT doesn't violate the
        // UNIQUE on stock_reservation_header.work_order_id.
        stockReservations.deleteHeaderAndLines(id);
        log.info("cancelled prior reservation {} for work_order={} (retry)", id, workOrderId);
    }

    private StockReservationLine reserveOneLine(
        UUID warehouseId,
        UUID lineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requested
    ) {
        BigDecimal available = balanceLookup.findAvailableQuantity(warehouseId, productId);
        BigDecimal reserved = available.compareTo(requested) >= 0 ? requested : available.max(BigDecimal.ZERO);
        BigDecimal shortage = requested.subtract(reserved);
        String status = shortage.signum() == 0 ? StockReservation.RESERVED
            : reserved.signum() == 0 ? StockReservation.FAILED
            : StockReservation.PARTIALLY_RESERVED;

        if (reserved.signum() > 0) {
            boolean ok = stockBalances.tryReserveOnHand(warehouseId, productId, reserved);
            if (!ok) {
                // Lost a race with another reservation. Treat as failed for
                // the slice; a real implementation would loop with backoff.
                reserved = BigDecimal.ZERO;
                shortage = requested;
                status = StockReservation.FAILED;
            }
        }

        return new StockReservationLine(
            lineId,
            productId,
            productSku,
            productName,
            requested,
            reserved,
            shortage,
            status
        );
    }

    private static List<Map<String, Object>> summary(List<StockReservationLine> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StockReservationLine l : lines) {
            out.add(Map.of(
                "sku", l.productSku(),
                "requested", l.requestedQuantity(),
                "reserved", l.reservedQuantity(),
                "shortage", l.shortageQuantity()
            ));
        }
        return out;
    }
}
