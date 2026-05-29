package com.northwood.inventory.application;

import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationLine;
import com.northwood.inventory.domain.StockReservationRepository;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.StockReservationRepository.ReservedLineSnapshot;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.inventory.application.replenishment.ReplenishmentDetectionService;
import com.northwood.shared.application.outbox.OutboxAppender;
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
    private final ReplenishmentDetectionService replenishmentDetection;
    private final OutboxAppender outbox;

    public StockReservationService(
        StockReservationRepository stockReservations,
        StockBalanceWriter stockBalances,
        StockBalanceLookup balanceLookup,
        WarehouseLookup warehouses,
        ReplenishmentDetectionService replenishmentDetection,
        OutboxAppender outbox
    ) {
        this.stockReservations = stockReservations;
        this.stockBalances = stockBalances;
        this.balanceLookup = balanceLookup;
        this.warehouses = warehouses;
        this.replenishmentDetection = replenishmentDetection;
        this.outbox = outbox;
    }

    @Transactional
    public void reserveForSalesOrder(StockReservationRequested payload) {
        UUID warehouseId = warehouses.findIdByCode(payload.warehouseCode() == null ? WarehouseCodes.MAIN : payload.warehouseCode());

        // §2.36 Slice E retry support: a SO saga that landed at
        // purchasing_requested and was un-parked by a ReplenishmentFulfilled
        // re-emits StockReservationRequested to retry reservation against the
        // now-restocked inventory. The schema's UNIQUE on
        // stock_reservation_header.sales_order_header_id blocks a second
        // insert, so drop any prior reservation for this SO and roll back the
        // reserved_quantity bumps it made before creating the new one. Mirror
        // of the work-order retry path that's been in place since the §2.9
        // shortage-recovery work.
        cancelPriorSalesOrderReservation(payload.salesOrderId(), warehouseId);

        List<StockReservationLine> lines = new ArrayList<>();
        for (StockReservationRequested.RequestedLine req : payload.lines()) {
            StockReservationLine line = reserveOneLine(
                warehouseId,
                UUID.randomUUID(),
                req.productId(), req.productSku(), req.productName(),
                req.requestedQuantity()
            );
            lines.add(line);

            // §2.37 Slice 3: inventory is the single make-vs-buy decision +
            // trigger point. Any line short on stock raises its replenishment
            // in THIS transaction (routing manufactured → manufacturing /
            // purchased → purchasing itself) with the sales-order back-reference
            // stamped, so the eventual ReplenishmentFulfilled un-parks the saga
            // (retry reservation) and ReplenishmentCancelled rejects the order.
            // Sales no longer drives manufacturing first — it just waits.
            if (line.shortageQuantity().signum() > 0) {
                replenishmentDetection.raiseForSalesOrderShortage(
                    req.productId(),
                    warehouseId,
                    line.shortageQuantity(),
                    payload.salesOrderId(),
                    req.salesOrderLineId()
                );
            }
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
        UUID warehouseId = warehouses.findIdByCode(payload.warehouseCode() == null ? WarehouseCodes.MAIN : payload.warehouseCode());

        // Phase 3 retry support: a work-order saga that previously landed
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

        InventorySalesOrderCancellationApplied ack = new InventorySalesOrderCancellationApplied(
            UUID.randomUUID(), salesOrderHeaderId, released, Instant.now()
        );
        // actor: saga-driven (inbox thread → no SecurityContext); propagation
        // from the inbound envelope is a B2 follow-up.
        outbox.append(ack, StockReservation.AGGREGATE_TYPE);
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

    /**
     * §2.36 Slice E: sibling of {@link #cancelPriorReservationFor(UUID, UUID)}
     * for the sales-order retry case. Removes any earlier-attempt reservation
     * for the same SO (e.g. the partial reservation from before the saga
     * rerouted to {@code purchasing_requested}) so the new attempt can claim
     * the now-restocked balance cleanly.
     */
    private void cancelPriorSalesOrderReservation(UUID salesOrderId, UUID warehouseId) {
        Optional<UUID> priorHeaderId = stockReservations.findAnyHeaderIdForSalesOrder(salesOrderId);
        if (priorHeaderId.isEmpty()) {
            return;
        }
        UUID id = priorHeaderId.get();
        for (ReservedLineSnapshot line : stockReservations.findReservedLines(id)) {
            stockBalances.releaseReserved(warehouseId, line.productId(), line.reservedQuantity());
        }
        stockReservations.deleteHeaderAndLines(id);
        log.info("cancelled prior reservation {} for sales_order={} (retry)", id, salesOrderId);
    }

    /**
     * §2.14: retry budget for a single line's reservation attempt against a
     * concurrent winner. Demo workload is single-tenant so the race window is
     * effectively never hit — values are sized for "make a reasonable effort
     * to recover from a brief race" rather than for production contention,
     * where they'd likely be tuned via {@code @Value} configuration.
     */
    static final int RESERVE_MAX_ATTEMPTS = 3;
    static final long[] RESERVE_BACKOFF_MS = { 10L, 40L, 160L };

    private StockReservationLine reserveOneLine(
        UUID warehouseId,
        UUID lineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requested
    ) {
        // §2.14: bounded retry with exponential backoff on lost-race against
        // a concurrent reservation. Each attempt re-reads available stock
        // and clamps the request to it — the winner of a race shrinks
        // (or zeroes) what's left on the next read, and we accept whatever
        // residual the retry can still secure.
        //
        // Termination paths:
        //   - tryReserveOnHand returns true → loop exits with that quantity.
        //   - available reads as zero (no stock left) → no point retrying; exit.
        //   - all RESERVE_MAX_ATTEMPTS exhausted → reserved stays at 0, status
        //     becomes FAILED below (same outcome as the pre-§2.14 single-shot
        //     code on race-loss).
        BigDecimal reserved = BigDecimal.ZERO;
        for (int attempt = 0; attempt < RESERVE_MAX_ATTEMPTS; attempt++) {
            BigDecimal available = balanceLookup.findAvailableQuantity(warehouseId, productId);
            BigDecimal toReserve = available.compareTo(requested) >= 0 ? requested : available.max(BigDecimal.ZERO);

            if (toReserve.signum() == 0) {
                // No stock left at all — not a race, just empty. Don't retry.
                break;
            }

            if (stockBalances.tryReserveOnHand(warehouseId, productId, toReserve)) {
                reserved = toReserve;
                break;
            }

            // Lost a race with a concurrent reservation. Back off and retry.
            log.debug("tryReserveOnHand lost race on product={} attempt={}/{} — backing off {}ms",
                productId, attempt + 1, RESERVE_MAX_ATTEMPTS, RESERVE_BACKOFF_MS[attempt]);
            if (attempt < RESERVE_MAX_ATTEMPTS - 1) {
                try {
                    Thread.sleep(RESERVE_BACKOFF_MS[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        BigDecimal shortage = requested.subtract(reserved);
        StockReservation.Status status = shortage.signum() == 0 ? StockReservation.Status.RESERVED
            : reserved.signum() == 0 ? StockReservation.Status.FAILED
            : StockReservation.Status.PARTIALLY_RESERVED;

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
