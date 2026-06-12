package com.northwood.sales.infrastructure.saga;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort.LineSnapshot;
import com.northwood.sales.application.saga.SalesOrderInvoiceSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderInvoiceSnapshotPort.OrderForPrepayment;
import com.northwood.sales.domain.PaymentTerms;
import com.northwood.sales.domain.events.DepositInvoiceRequested;
import com.northwood.sales.domain.events.PrepaymentInvoiceRequested;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.sales.domain.saga.FulfilmentSagaData;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.AWAITING_PREPAYMENT;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.AWAITING_RELEASE;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.PREPAID;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STARTED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Spring scheduling glue + worker-driven advance for the sales fulfilment
 * saga. The {@code @Scheduled poll()} delegates to the manager's drain
 * machinery (per-saga REQUIRES_NEW + retry) and provides the per-saga
 * advance step as a callback.
 *
 * <p>Worker-driven advances:
 * <ul>
 *   <li>{@code started → stock_reservation_requested}: read sales-order
 *       lines, emit {@code StockReservationRequested}, transition saga.</li>
 *   <li>{@code prepaid → stock_reservation_requested}: same, after a
 *       prepayment invoice has been fully paid.</li>
 * </ul>
 *
 * <p>The {@code stock_reservation_incomplete → manufacturing_requested} leg
 * has been removed: inventory now raises the {@code ReplenishmentRequest} in
 * the same transaction as the partial reservation, so the worker no longer
 * forwards shortages to manufacturing.
 * The saga parks at {@code stock_reservation_incomplete} until an
 * {@code inventory.ReplenishmentFulfilled} / {@code ReplenishmentCancelled}
 * drives it via the inbox.
 *
 * <p>The drain machinery commits saga state via {@code port.save(saga)}
 * after this callback returns, so transitions made here land atomically
 * with the outbox writes inside the same per-saga transaction.
 */
@Component
public class SalesOrderFulfilmentSagaWorker {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderFulfilmentSagaWorker.class);
    private static final int BATCH_SIZE = 10;

    private final String workerId =
        "sales.fulfilment-worker@" + ManagementFactory.getRuntimeMXBean().getName();

    private final SalesOrderFulfilmentSagaManager manager;
    private final SalesOrderLineSnapshotPort lineSnapshots;
    private final SalesOrderInvoiceSnapshotPort invoiceSnapshots;
    private final OutboxAppender outbox;
    private final ObjectMapper json;
    // Time source for the planning-time-fence gate decision + all park/event
    // timestamps. Injected (InstantSource.system() in prod) so the decide-once
    // gate is a pure unit test with a fixed clock — see docs/sagas.md → Timed
    // releases. The wake itself is driven by the DB clock (next_retry_at <=
    // now()), so the gate is evaluated against this clock exactly once, on entry.
    private final InstantSource clock;

    public SalesOrderFulfilmentSagaWorker(
        SalesOrderFulfilmentSagaManager manager,
        SalesOrderLineSnapshotPort lineSnapshots,
        SalesOrderInvoiceSnapshotPort invoiceSnapshots,
        OutboxAppender outbox,
        ObjectMapper json,
        InstantSource clock
    ) {
        this.manager = manager;
        this.lineSnapshots = lineSnapshots;
        this.invoiceSnapshots = invoiceSnapshots;
        this.outbox = outbox;
        this.json = json;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${northwood.saga.poll-interval:1000}")
    public void poll() {
        manager.drain(BATCH_SIZE, workerId, this::advance);
    }

    /** Test-side hook: drive one batch with a deterministic worker id. */
    public void drainOnce(String workerId) {
        manager.drain(BATCH_SIZE, workerId, this::advance);
    }

    private void advance(SalesOrderFulfilmentSaga saga) {
        switch (saga.state()) {
            case STARTED -> advanceFromStarted(saga);
            case PREPAID -> requestStockReservation(saga);
            // Decide-once: woken from awaiting_release means the release date
            // arrived (the DB poll said so) — emit unconditionally, no re-gate.
            case AWAITING_RELEASE -> emitDeferredStockReservation(saga);
            default -> log.debug("[{}] no transition implemented for state {}", workerId, saga.state());
        }
    }

    /**
     * Branch at {@code started} on the order's payment terms (snapshotted onto
     * saga.data at saga creation). {@code on_shipment} → existing
     * stock-reservation request. {@code prepayment} / {@code deposit} → emit the
     * up-front invoice request and park at {@code awaiting_prepayment} until the
     * up-front payment settles; the payment handler then flips the saga to
     * {@code prepaid}, where the worker walks the same
     * {@link #requestStockReservation} path as the on-shipment flow. (The
     * invoice-created intermediate is no longer tracked — the gate waits directly
     * for payment.)
     *
     * <p>Legacy sagas (paymentTerms unset) take the on-shipment path.
     */
    private void advanceFromStarted(SalesOrderFulfilmentSaga saga) {
        String pt = readData(saga).paymentTerms();
        if (PaymentTerms.PREPAYMENT.code().equals(pt)) {
            requestPrepaymentInvoice(saga);
        } else if (PaymentTerms.DEPOSIT.code().equals(pt)) {
            requestDepositInvoice(saga);
        } else {
            requestStockReservation(saga);
        }
    }

    /**
     * Branch at {@code started} for deposit orders: compute the up-front
     * deposit ({@code total × deposit_percent / 100}) and emit
     * {@code DepositInvoiceRequested}, parking at {@code awaiting_prepayment}
     * until the deposit payment settles. The worker picks the saga back up from
     * {@code prepaid} (after the deposit is settled) to walk the same
     * {@link #requestStockReservation} path as on-shipment; the balance invoice +
     * payment land after shipment and fold into the completion gate.
     */
    private void requestDepositInvoice(SalesOrderFulfilmentSaga saga) {
        UUID salesOrderId = saga.salesOrderId();
        OrderForPrepayment order = invoiceSnapshots.findOrderForPrepayment(salesOrderId)
            .orElseThrow(() -> new IllegalStateException(
                "No sales-order header found for sales_order_header_id=" + salesOrderId
                    + " — cannot request deposit invoice"));
        Assert.stateNotEmpty(order.lines(), "No lines found for sales_order_header_id=" + salesOrderId
            + " — cannot request deposit invoice");
        Assert.stateNotNull(order.depositPercent(), "deposit order " + salesOrderId
            + " has no deposit_percent — cannot compute the deposit amount");

        BigDecimal depositAmount = orderTotal(order)
            .multiply(order.depositPercent())
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        DepositInvoiceRequested event = new DepositInvoiceRequested(
            UUID.randomUUID(),
            salesOrderId,
            order.orderNumber(),
            order.customerId(),
            order.customerCode(),
            order.customerName(),
            order.currencyCode(),
            depositAmount,
            order.depositPercent(),
            clock.instant()
        );
        outbox.append(event, SalesOrderFulfilmentSaga.AGGREGATE_TYPE);

        saga.transitionTo(AWAITING_PREPAYMENT, "wait_for_deposit_payment");
        saga.parkUntil(clock.instant().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} sales_order={} → awaiting_prepayment (deposit {}% = {} {})",
            workerId, saga.sagaId(), salesOrderId, order.depositPercent(), depositAmount, order.currencyCode());
    }

    /** Order total (subtotal + tax) from the priced lines, rounded to 2dp. */
    private static BigDecimal orderTotal(OrderForPrepayment order) {
        BigDecimal total = BigDecimal.ZERO;
        for (SalesOrderInvoiceSnapshotPort.PricedLine l : order.lines()) {
            BigDecimal lineSubtotal = l.orderedQuantity().multiply(l.unitPrice());
            BigDecimal taxRate = l.taxRate() == null ? BigDecimal.ZERO : l.taxRate();
            total = total.add(lineSubtotal).add(lineSubtotal.multiply(taxRate));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private void requestPrepaymentInvoice(SalesOrderFulfilmentSaga saga) {
        UUID salesOrderId = saga.salesOrderId();
        OrderForPrepayment order = invoiceSnapshots.findOrderForPrepayment(salesOrderId)
            .orElseThrow(() -> new IllegalStateException(
                "No sales-order header found for sales_order_header_id=" + salesOrderId
                    + " — cannot request prepayment invoice"));
        Assert.stateNotEmpty(order.lines(), "No lines found for sales_order_header_id=" + salesOrderId
            + " — cannot request prepayment invoice");

        List<PrepaymentInvoiceRequested.RequestedLine> lines = new ArrayList<>();
        for (SalesOrderInvoiceSnapshotPort.PricedLine l : order.lines()) {
            lines.add(new PrepaymentInvoiceRequested.RequestedLine(
                l.salesOrderLineId(),
                l.lineNumber(),
                l.productId(),
                l.productSku(),
                l.productName(),
                l.orderedQuantity(),
                l.unitPrice(),
                l.taxRate() == null ? BigDecimal.ZERO : l.taxRate()
            ));
        }

        PrepaymentInvoiceRequested event = new PrepaymentInvoiceRequested(
            UUID.randomUUID(),
            salesOrderId,
            order.orderNumber(),
            order.customerId(),
            order.customerCode(),
            order.customerName(),
            order.currencyCode(),
            lines,
            clock.instant()
        );
        outbox.append(event, SalesOrderFulfilmentSaga.AGGREGATE_TYPE);

        saga.transitionTo(AWAITING_PREPAYMENT, "wait_for_prepayment_payment");
        saga.parkUntil(clock.instant().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} sales_order={} → awaiting_prepayment ({} line(s); prepayment flow)",
            workerId, saga.sagaId(), salesOrderId, lines.size());
    }

    /**
     * Entry to the reservation leg. Applies the planning-time-fence gate
     * <em>once</em>: if the order's need-by is far enough out that
     * {@code need-by − max(line fence)} is still in the future, park at
     * {@code awaiting_release} until that instant; otherwise emit the
     * reservation immediately (today's behaviour for fence-0 / dateless orders).
     * Reached from {@code started} (on-shipment) and from {@code prepaid} after
     * the up-front payment (prepayment or deposit) settles.
     */
    private void requestStockReservation(SalesOrderFulfilmentSaga saga) {
        UUID salesOrderId = saga.salesOrderId();
        List<LineSnapshot> snapshots = lineSnapshots.findLines(salesOrderId);
        Assert.stateNotEmpty(snapshots, "No lines found for sales_order_header_id=" + salesOrderId + " — cannot request reservation");

        Instant releaseAt = computeReleaseAt(saga, snapshots);
        if (releaseAt != null && clock.instant().isBefore(releaseAt)) {
            saga.transitionTo(AWAITING_RELEASE, "wait_for_planning_fence_release");
            saga.parkUntil(releaseAt);
            log.info("[{}] saga {} sales_order={} → awaiting_release (releases {})",
                workerId, saga.sagaId(), salesOrderId, releaseAt);
            return;
        }

        emitStockReservationRequested(saga, snapshots);
    }

    /**
     * Wake from {@code awaiting_release}: the poll re-claimed this row because
     * {@code next_retry_at <= now()}, i.e. the release date arrived. Emit the
     * deferred reservation <em>without</em> re-evaluating the fence — the park
     * was the decision (decide-once; see {@code docs/sagas.md} → Timed
     * releases). Re-gating here against the Java clock would risk re-parking on
     * any clock skew and never releasing.
     */
    private void emitDeferredStockReservation(SalesOrderFulfilmentSaga saga) {
        UUID salesOrderId = saga.salesOrderId();
        List<LineSnapshot> snapshots = lineSnapshots.findLines(salesOrderId);
        Assert.stateNotEmpty(snapshots, "No lines found for sales_order_header_id=" + salesOrderId + " — cannot request reservation");
        emitStockReservationRequested(saga, snapshots);
    }

    /**
     * Build + emit {@code StockReservationRequested} for the order's lines and
     * park awaiting {@code StockReserved}. Shared by the immediate path and the
     * {@code awaiting_release} wake.
     */
    private void emitStockReservationRequested(SalesOrderFulfilmentSaga saga, List<LineSnapshot> snapshots) {
        UUID salesOrderId = saga.salesOrderId();
        List<StockReservationRequested.RequestedLine> lines = new ArrayList<>();
        for (LineSnapshot s : snapshots) {
            lines.add(new StockReservationRequested.RequestedLine(
                s.salesOrderLineId(),
                s.lineNumber(),
                s.productId(),
                s.productSku(),
                s.productName(),
                s.orderedQuantity(),
                s.pegged()
            ));
        }

        StockReservationRequested event = new StockReservationRequested(
            UUID.randomUUID(),
            salesOrderId,
            salesOrderId,
            WarehouseCodes.MAIN,
            lines,
            clock.instant()
        );
        outbox.append(event, SalesOrderFulfilmentSaga.AGGREGATE_TYPE);

        saga.transitionTo(STOCK_RESERVATION_REQUESTED, "wait_for_stock_reserved");
        saga.parkUntil(clock.instant().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} sales_order={} → stock_reservation_requested ({} line(s))",
            workerId, saga.sagaId(), salesOrderId, lines.size());
    }

    /**
     * Planning-time-fence release instant = {@code need-by − max(line fence)},
     * at UTC start-of-day. Returns {@code null} — meaning reserve immediately —
     * when the order carries no need-by (no gating) or every line's fence is 0
     * (today's default behaviour). Need-by is read from {@code saga.data}
     * (stamped at placement); the per-line fence comes from the line snapshots
     * (read live from {@code product_card}); the whole-order reservation is
     * gated by the earliest-needed line, i.e. the largest fence.
     */
    private Instant computeReleaseAt(SalesOrderFulfilmentSaga saga, List<LineSnapshot> snapshots) {
        String needBy = readData(saga).requestedDeliveryDate();
        if (needBy == null) {
            return null;
        }
        int maxFenceDays = snapshots.stream()
            .mapToInt(LineSnapshot::planningTimeFenceDays)
            .max()
            .orElse(0);
        if (maxFenceDays <= 0) {
            return null;
        }
        return LocalDate.parse(needBy)
            .minusDays(maxFenceDays)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
    }

    private FulfilmentSagaData readData(SalesOrderFulfilmentSaga saga) {
        String raw = saga.dataJson();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return FulfilmentSagaData.none();
        }
        try {
            FulfilmentSagaData data = json.readValue(raw, FulfilmentSagaData.class);
            return data == null ? FulfilmentSagaData.none() : data;
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot parse saga.data: " + raw, e);
        }
    }

}
