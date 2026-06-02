package com.northwood.inventory.application;

import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.inventory.domain.ReplenishmentRequest;
import com.northwood.inventory.domain.ReplenishmentRequestRepository;
import com.northwood.inventory.domain.StockReservation;
import com.northwood.inventory.domain.StockReservationLine;
import com.northwood.inventory.domain.StockReservationRepository;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.StockReservationRepository.ReservedLineSnapshot;
import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
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
    private final ReplenishmentRequestRepository replenishmentRequests;
    private final OutboxAppender outbox;

    public StockReservationService(
        StockReservationRepository stockReservations,
        StockBalanceWriter stockBalances,
        StockBalanceLookup balanceLookup,
        WarehouseLookup warehouses,
        ReplenishmentDetectionService replenishmentDetection,
        ReplenishmentRequestRepository replenishmentRequests,
        OutboxAppender outbox
    ) {
        this.stockReservations = stockReservations;
        this.stockBalances = stockBalances;
        this.balanceLookup = balanceLookup;
        this.warehouses = warehouses;
        this.replenishmentDetection = replenishmentDetection;
        this.replenishmentRequests = replenishmentRequests;
        this.outbox = outbox;
    }

    @Transactional
    public void reserveForSalesOrder(StockReservationRequested payload) {
        UUID warehouseId = warehouses.findIdByCode(payload.warehouseCode() == null ? WarehouseCodes.MAIN : payload.warehouseCode());

        // SO saga retry support: a SO saga that landed at purchasing_requested
        // and was un-parked by a ReplenishmentFulfilled re-emits
        // StockReservationRequested to retry reservation against the
        // now-restocked inventory. The schema's UNIQUE on
        // stock_reservation_header.sales_order_header_id blocks a second
        // insert, so drop any prior reservation for this SO and roll back the
        // reserved_quantity bumps it made before creating the new one. Mirror
        // of the work-order retry path that's been in place since the
        // shortage-recovery work.
        cancelPriorSalesOrderReservation(payload.salesOrderId(), warehouseId);

        List<StockReservationLine> lines = new ArrayList<>();
        for (StockReservationRequested.RequestedLine req : payload.lines()) {
            if (req.pegged()) {
                // Order-pegged (to_order, §2.43): the line NEVER draws from the
                // shared pool. Record it as a full-shortage (zero-reserved) line
                // and raise a dedicated, order-pegged replenishment for the FULL
                // line qty, routed by make-vs-buy. Peg-on-completion (slices C/D)
                // reserves the eventual output for this SO line; until then the
                // line behaves to sales exactly like an awaiting-replenishment
                // shortage line (parks the saga at stock_reservation_incomplete).
                lines.add(new StockReservationLine(
                    UUID.randomUUID(),
                    req.productId(), req.productSku(), req.productName(),
                    req.requestedQuantity(), BigDecimal.ZERO, req.requestedQuantity(),
                    StockReservation.Status.FAILED
                ));
                replenishmentDetection.raiseForOrderPegged(
                    req.productId(),
                    warehouseId,
                    req.requestedQuantity(),
                    payload.salesOrderId(),
                    req.salesOrderLineId()
                );
                continue;
            }

            StockReservationLine line = reserveOneLine(
                warehouseId,
                UUID.randomUUID(),
                req.productId(), req.productSku(), req.productName(),
                req.requestedQuantity()
            );
            lines.add(line);

            // Inventory is the single make-vs-buy decision + trigger point.
            // Any line short on stock raises its replenishment in THIS
            // transaction (routing manufactured → manufacturing / purchased →
            // purchasing itself) with the sales-order back-reference stamped,
            // so the eventual ReplenishmentFulfilled un-parks the saga (retry
            // reservation) and ReplenishmentCancelled rejects the order.
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

        unpegForSalesOrder(salesOrderHeaderId);

        InventorySalesOrderCancellationApplied ack = new InventorySalesOrderCancellationApplied(
            UUID.randomUUID(), salesOrderHeaderId, released, Instant.now()
        );
        // actor: saga-driven (inbox thread → no SecurityContext); propagation
        // from the inbound envelope is a B2 follow-up.
        outbox.append(ack, StockReservation.AGGREGATE_TYPE);
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

    /**
     * Un-peg a cancelled sales order's order-pegged ({@code to_order}, §2.43)
     * supply. The peg-on-completion (slices C/D) reserves the built/bought
     * output on {@code stock_balance.reserved_quantity} — <em>outside</em> the
     * {@code StockReservation} row {@link #unwindReservation} unwinds — so cancel
     * must release it explicitly:
     * <ul>
     *   <li>{@code FULFILLED} → the peg already reserved {@code requestedQuantity};
     *       release it so the stock returns to the free pool (un-peg → the SKU
     *       behaves as make-to-stock from here, no write-off for standard
     *       catalogue items);</li>
     *   <li>{@code REQUESTED}/{@code DISPATCHED} (in-flight WO/PO) → drop the peg
     *       by cancelling the request: nothing is reserved yet, and the eventual
     *       completion credits stock to the pool but no longer pegs/fulfils (the
     *       completion handlers act only on a {@code DISPATCHED} request);</li>
     *   <li>{@code CANCELLED} → idempotent no-op (redelivered cancel).</li>
     * </ul>
     * Sales-order-shortage replenishments are untouched — their reserve lands in
     * the {@code StockReservation} row and is released by {@link #unwindReservation}.
     */
    private void unpegForSalesOrder(UUID salesOrderHeaderId) {
        for (ReplenishmentRequest r : replenishmentRequests.findOrderPeggedForSalesOrder(salesOrderHeaderId)) {
            switch (r.status()) {
                case FULFILLED -> {
                    stockBalances.releaseReserved(r.warehouseId(), r.productId(), r.requestedQuantity());
                    log.info("un-pegged {} of product={} for cancelled sales_order={} → returned to free pool",
                        r.requestedQuantity(), r.productId(), salesOrderHeaderId);
                }
                case REQUESTED, DISPATCHED -> {
                    String priorStatus = r.status().dbValue();
                    r.markCancelled("sales order " + salesOrderHeaderId
                        + " cancelled — dropping order-pegged supply (settles into free pool)");
                    replenishmentRequests.save(r);
                    log.info("dropped in-flight order-pegged replenishment={} (status was {}) for cancelled sales_order={}",
                        r.id().value(), priorStatus, salesOrderHeaderId);
                }
                case CANCELLED -> { /* already cancelled — idempotent */ }
            }
        }
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
     * Sibling of {@link #cancelPriorReservationFor(UUID, UUID)} for the
     * sales-order retry case. Removes any earlier-attempt reservation for the
     * same SO (e.g. the partial reservation from before the saga rerouted to
     * {@code purchasing_requested}) so the new attempt can claim the
     * now-restocked balance cleanly.
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
     * Retry budget for a single line's reservation attempt against a concurrent
     * winner. Demo workload is single-tenant so the race window is effectively
     * never hit — values are sized for "make a reasonable effort to recover from
     * a brief race" rather than for production contention, where they'd likely be
     * tuned via {@code @Value} configuration.
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
        // Bounded retry with exponential backoff on lost-race against a
        // concurrent reservation. Each attempt re-reads available stock and
        // clamps the request to it — the winner of a race shrinks (or zeroes)
        // what's left on the next read, and we accept whatever residual the
        // retry can still secure.
        //
        // Termination paths:
        //   - tryReserveOnHand returns true → loop exits with that quantity.
        //   - available reads as zero (no stock left) → no point retrying; exit.
        //   - all RESERVE_MAX_ATTEMPTS exhausted → reserved stays at 0, status
        //     becomes FAILED below.
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
