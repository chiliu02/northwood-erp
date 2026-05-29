package com.northwood.sales.infrastructure.saga;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort.LineSnapshot;
import com.northwood.sales.application.saga.SalesOrderInvoiceSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderInvoiceSnapshotPort.OrderForPrepayment;
import com.northwood.sales.domain.events.PrepaymentInvoiceRequested;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.sales.domain.saga.FulfilmentSagaData;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.AWAITING_PREPAYMENT_INVOICE;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.PREPAID;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STARTED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED;
import com.northwood.shared.domain.Assert;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 *       prepayment invoice has been fully paid (§2.31 Slice B).</li>
 * </ul>
 *
 * <p>§2.37 Slice 3 removed the {@code stock_reservation_incomplete →
 * manufacturing_requested} leg: inventory now raises the
 * {@code ReplenishmentRequest} in the same transaction as the partial
 * reservation, so the worker no longer forwards shortages to manufacturing.
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

    public SalesOrderFulfilmentSagaWorker(
        SalesOrderFulfilmentSagaManager manager,
        SalesOrderLineSnapshotPort lineSnapshots,
        SalesOrderInvoiceSnapshotPort invoiceSnapshots,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        this.manager = manager;
        this.lineSnapshots = lineSnapshots;
        this.invoiceSnapshots = invoiceSnapshots;
        this.outbox = outbox;
        this.json = json;
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
            default -> log.debug("[{}] no transition implemented for state {}", workerId, saga.state());
        }
    }

    /**
     * §2.31 Slice B. Branch at {@code started} on the order's payment terms
     * (snapshotted onto saga.data at saga creation). {@code on_shipment} →
     * existing stock-reservation request. {@code prepayment} → emit
     * {@code PrepaymentInvoiceRequested}, park at
     * {@code awaiting_prepayment_invoice} until finance acks with
     * {@code CustomerInvoiceCreated}; the worker picks the saga back up after
     * payment receipt flips it to {@code prepaid}, at which point we walk
     * the same {@link #requestStockReservation} path as the on-shipment flow.
     *
     * <p>Legacy sagas (paymentTerms unset) take the on-shipment path — the
     * only flow that existed pre-§2.31 Slice B.
     */
    private void advanceFromStarted(SalesOrderFulfilmentSaga saga) {
        String pt = readData(saga).paymentTerms();
        if (SalesOrderPlaced.PAYMENT_TERMS_PREPAYMENT.equals(pt)) {
            requestPrepaymentInvoice(saga);
        } else {
            requestStockReservation(saga);
        }
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
            Instant.now()
        );
        outbox.append(event, SalesOrderFulfilmentSaga.AGGREGATE_TYPE);

        saga.transitionTo(AWAITING_PREPAYMENT_INVOICE, "wait_for_prepayment_invoice");
        saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} sales_order={} → awaiting_prepayment_invoice ({} line(s); prepayment flow)",
            workerId, saga.sagaId(), salesOrderId, lines.size());
    }

    private void requestStockReservation(SalesOrderFulfilmentSaga saga) {
        UUID salesOrderId = saga.salesOrderId();
        List<LineSnapshot> snapshots = lineSnapshots.findLines(salesOrderId);
        Assert.stateNotEmpty(snapshots, "No lines found for sales_order_header_id=" + salesOrderId + " — cannot request reservation");

        List<StockReservationRequested.RequestedLine> lines = new ArrayList<>();
        for (LineSnapshot s : snapshots) {
            lines.add(new StockReservationRequested.RequestedLine(
                s.salesOrderLineId(),
                s.lineNumber(),
                s.productId(),
                s.productSku(),
                s.productName(),
                s.orderedQuantity()
            ));
        }

        StockReservationRequested event = new StockReservationRequested(
            UUID.randomUUID(),
            salesOrderId,
            salesOrderId,
            WarehouseCodes.MAIN,
            lines,
            Instant.now()
        );
        outbox.append(event, SalesOrderFulfilmentSaga.AGGREGATE_TYPE);

        saga.transitionTo(STOCK_RESERVATION_REQUESTED, "wait_for_stock_reserved");
        saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} sales_order={} → stock_reservation_requested ({} line(s))",
            workerId, saga.sagaId(), salesOrderId, lines.size());
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
