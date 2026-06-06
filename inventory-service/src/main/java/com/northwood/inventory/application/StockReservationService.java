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
import com.northwood.inventory.domain.events.SalesOrderLineReservationChanged;
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
                // Order-pegged (to_order): the line NEVER draws from the
                // shared pool. Record it as a full-shortage (zero-reserved) line
                // and raise a dedicated, order-pegged replenishment for the FULL
                // line qty, routed by make-vs-buy. Peg-on-completion
                // reserves the eventual output for this SO line; until then the
                // line behaves to sales exactly like an awaiting-replenishment
                // shortage line (parks the saga at stock_reservation_incomplete).
                lines.add(new StockReservationLine(
                    UUID.randomUUID(),
                    req.salesOrderLineId(),
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
                req.salesOrderLineId(),
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
                null,   // work-order line — no sales_order_line_id
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

    // ============================================================
    // §1G line amendment — incremental per-line reserve / release / delta.
    //
    // Each op no-ops when the order has no live reservation yet: the amendment
    // raced ahead of inventory's first reservation, and the eventual
    // StockReservationRequested (re-)reads the full current line set from sales'
    // LineSnapshot, so the line is covered there. When a reservation does exist,
    // the op adjusts only the affected line + stock_balance, recomputes the
    // header status, and emits SalesOrderLineReservationChanged so the saga can
    // reconcile its outstanding-line set.
    // ============================================================

    /** A line was added to a sales order: reserve it against the existing reservation. */
    @Transactional
    public void applyLineAdded(
        UUID salesOrderId,
        UUID salesOrderLineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal quantity
    ) {
        Optional<UUID> headerId = stockReservations.findLiveHeaderIdForSalesOrder(salesOrderId);
        if (headerId.isEmpty()) {
            log.info("no live reservation for sales_order={}; added line {} will be covered by the (re-)reservation",
                salesOrderId, salesOrderLineId);
            return;
        }
        if (stockReservations.findAmendableSalesOrderLine(salesOrderId, salesOrderLineId).isPresent()) {
            log.debug("reservation line for sales_order={} line={} already exists — idempotent no-op",
                salesOrderId, salesOrderLineId);
            return;
        }
        UUID warehouseId = stockReservations.findWarehouseIdForHeader(headerId.get())
            .orElseThrow(() -> new IllegalStateException("Reservation header " + headerId.get() + " has no warehouse"));

        BigDecimal reserved = reserveUpTo(warehouseId, productId, quantity);
        BigDecimal shortage = quantity.subtract(reserved);
        StockReservation.Status status = statusFor(quantity, reserved);
        stockReservations.appendLine(headerId.get(), new StockReservationLine(
            UUID.randomUUID(), salesOrderLineId, productId, productSku, productName,
            quantity, reserved, shortage, status
        ));
        if (shortage.signum() > 0) {
            replenishmentDetection.raiseForSalesOrderShortage(productId, warehouseId, shortage, salesOrderId, salesOrderLineId);
        }
        stockReservations.recomputeSalesOrderHeaderStatus(headerId.get());
        emitLineReservationChanged(salesOrderId, salesOrderLineId, productId, reserved, shortage, status.dbValue());
        log.info("amended sales_order={} +line={} reserved={} shortage={} status={}",
            salesOrderId, salesOrderLineId, reserved, shortage, status.dbValue());
    }

    /** A line was removed: release its reservation back to the free pool. */
    @Transactional
    public void applyLineRemoved(UUID salesOrderId, UUID salesOrderLineId) {
        Optional<StockReservationRepository.AmendableSalesOrderLine> opt =
            stockReservations.findAmendableSalesOrderLine(salesOrderId, salesOrderLineId);
        if (opt.isEmpty()) {
            log.info("no live reservation line for sales_order={} line={} to release — no-op", salesOrderId, salesOrderLineId);
            return;
        }
        StockReservationRepository.AmendableSalesOrderLine l = opt.get();
        if (l.reservedQuantity().signum() > 0) {
            stockBalances.releaseReserved(l.warehouseId(), l.productId(), l.reservedQuantity());
        }
        stockReservations.updateLine(
            l.stockReservationLineId(), l.requestedQuantity(), BigDecimal.ZERO, BigDecimal.ZERO,
            StockReservation.Status.RELEASED.dbValue()
        );
        stockReservations.recomputeSalesOrderHeaderStatus(l.stockReservationHeaderId());
        // Emit the released reply FIRST so the saga drops this line from its
        // outstanding set before the (below) ReplenishmentCancelled lands — a
        // cancel for a no-longer-outstanding line is a benign no-op rather than
        // an order rejection.
        emitLineReservationChanged(salesOrderId, salesOrderLineId, l.productId(),
            BigDecimal.ZERO, BigDecimal.ZERO, StockReservation.Status.RELEASED.dbValue());
        // §1G Slice C: if the removed line was short and awaiting supply, cancel
        // its in-flight replenishment so it doesn't fulfil into the pool for a
        // line that no longer exists. A fulfilled request is excluded by the
        // finder (its stock already landed; the line was re-reserved + released).
        cancelOpenReplenishmentForLine(salesOrderId, salesOrderLineId);
        log.info("amended sales_order={} -line={} released reserved={}",
            salesOrderId, salesOrderLineId, l.reservedQuantity());
    }

    private void cancelOpenReplenishmentForLine(UUID salesOrderId, UUID salesOrderLineId) {
        for (ReplenishmentRequest r : replenishmentRequests.findOpenForSalesOrderLine(salesOrderId, salesOrderLineId)) {
            r.markCancelled("sales order " + salesOrderId + " line " + salesOrderLineId
                + " removed — dropping its in-flight replenishment");
            replenishmentRequests.save(r);
            log.info("cancelled in-flight replenishment={} for removed sales_order={} line={}",
                r.id().value(), salesOrderId, salesOrderLineId);
        }
    }

    /** A line's quantity changed: reserve the delta on an increase, release it on a decrease. */
    @Transactional
    public void applyLineQuantityChanged(UUID salesOrderId, UUID salesOrderLineId, BigDecimal newQuantity) {
        Optional<StockReservationRepository.AmendableSalesOrderLine> opt =
            stockReservations.findAmendableSalesOrderLine(salesOrderId, salesOrderLineId);
        if (opt.isEmpty()) {
            log.info("no live reservation line for sales_order={} line={} to adjust — no-op", salesOrderId, salesOrderLineId);
            return;
        }
        StockReservationRepository.AmendableSalesOrderLine l = opt.get();
        BigDecimal oldReserved = l.reservedQuantity();
        BigDecimal reserved;
        if (newQuantity.compareTo(oldReserved) <= 0) {
            BigDecimal release = oldReserved.subtract(newQuantity);
            if (release.signum() > 0) {
                stockBalances.releaseReserved(l.warehouseId(), l.productId(), release);
            }
            reserved = newQuantity;
        } else {
            BigDecimal got = reserveUpTo(l.warehouseId(), l.productId(), newQuantity.subtract(oldReserved));
            reserved = oldReserved.add(got);
        }
        BigDecimal shortage = newQuantity.subtract(reserved);
        StockReservation.Status status = statusFor(newQuantity, reserved);
        stockReservations.updateLine(l.stockReservationLineId(), newQuantity, reserved, shortage, status.dbValue());
        if (shortage.signum() > 0) {
            replenishmentDetection.raiseForSalesOrderShortage(l.productId(), l.warehouseId(), shortage, salesOrderId, salesOrderLineId);
        }
        stockReservations.recomputeSalesOrderHeaderStatus(l.stockReservationHeaderId());
        emitLineReservationChanged(salesOrderId, salesOrderLineId, l.productId(), reserved, shortage, status.dbValue());
        log.info("amended sales_order={} ~line={} newQty={} reserved={} shortage={} status={}",
            salesOrderId, salesOrderLineId, newQuantity, reserved, shortage, status.dbValue());
    }

    private void emitLineReservationChanged(
        UUID salesOrderId, UUID salesOrderLineId, UUID productId,
        BigDecimal reserved, BigDecimal shortage, String status
    ) {
        outbox.append(new SalesOrderLineReservationChanged(
            UUID.randomUUID(), salesOrderId, salesOrderLineId, productId,
            reserved, shortage, status, Instant.now()
        ), StockReservation.AGGREGATE_TYPE);
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
     * Un-peg a cancelled sales order's order-pegged ({@code to_order})
     * supply. The peg-on-completion reserves the built/bought
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
        UUID salesOrderLineId,
        UUID productId,
        String productSku,
        String productName,
        BigDecimal requested
    ) {
        BigDecimal reserved = reserveUpTo(warehouseId, productId, requested);
        BigDecimal shortage = requested.subtract(reserved);
        StockReservation.Status status = statusFor(requested, reserved);
        return new StockReservationLine(
            lineId,
            salesOrderLineId,
            productId,
            productSku,
            productName,
            requested,
            reserved,
            shortage,
            status
        );
    }

    /**
     * Reserve up to {@code want} from the free pool, returning how much was
     * actually secured (0..{@code want}). Bounded retry with exponential backoff
     * on a lost race against a concurrent reservation — each attempt re-reads
     * available stock and clamps the request to it.
     *
     * <p>Termination: {@code tryReserveOnHand} wins → return that quantity;
     * available reads zero (no stock) → return 0; attempts exhausted → return 0.
     */
    private BigDecimal reserveUpTo(UUID warehouseId, UUID productId, BigDecimal want) {
        for (int attempt = 0; attempt < RESERVE_MAX_ATTEMPTS; attempt++) {
            BigDecimal available = balanceLookup.findAvailableQuantity(warehouseId, productId);
            BigDecimal toReserve = available.compareTo(want) >= 0 ? want : available.max(BigDecimal.ZERO);

            if (toReserve.signum() == 0) {
                break;
            }
            if (stockBalances.tryReserveOnHand(warehouseId, productId, toReserve)) {
                return toReserve;
            }
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
        return BigDecimal.ZERO;
    }

    private static StockReservation.Status statusFor(BigDecimal requested, BigDecimal reserved) {
        BigDecimal shortage = requested.subtract(reserved);
        return shortage.signum() == 0 ? StockReservation.Status.RESERVED
            : reserved.signum() == 0 ? StockReservation.Status.FAILED
            : StockReservation.Status.PARTIALLY_RESERVED;
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
