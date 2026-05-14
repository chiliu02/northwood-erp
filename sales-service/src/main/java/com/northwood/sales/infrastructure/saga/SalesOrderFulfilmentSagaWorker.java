package com.northwood.sales.infrastructure.saga;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort.LineSnapshot;
import com.northwood.sales.domain.events.ManufacturingRequested;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.sales.domain.saga.FulfilmentSagaData;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.MANUFACTURING_REQUESTED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STARTED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVED;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code stock_reserved → manufacturing_requested}: read sales-order
 *       lines, filter by shortage (when present), emit
 *       {@code ManufacturingRequested}, transition saga.</li>
 * </ul>
 *
 * <p>The drain machinery commits saga state via {@code port.save(saga)}
 * after this callback returns, so transitions made here land atomically
 * with the outbox writes inside the same per-saga transaction.
 */
@Component
public class SalesOrderFulfilmentSagaWorker {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderFulfilmentSagaWorker.class);
    private static final int BATCH_SIZE = 10;
    private static final String DEFAULT_WAREHOUSE = "MAIN";

    private final String workerId =
        "sales.fulfilment-worker@" + ManagementFactory.getRuntimeMXBean().getName();

    private final SalesOrderFulfilmentSagaManager manager;
    private final SalesOrderLineSnapshotPort lineSnapshots;
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public SalesOrderFulfilmentSagaWorker(
        SalesOrderFulfilmentSagaManager manager,
        SalesOrderLineSnapshotPort lineSnapshots,
        OutboxPort outbox,
        ObjectMapper json
    ) {
        this.manager = manager;
        this.lineSnapshots = lineSnapshots;
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
            case STARTED -> requestStockReservation(saga);
            case STOCK_RESERVED -> requestManufacturing(saga);
            default -> log.debug("[{}] no transition implemented for state {}", workerId, saga.state());
        }
    }

    private void requestStockReservation(SalesOrderFulfilmentSaga saga) {
        UUID salesOrderId = saga.salesOrderId();
        List<LineSnapshot> snapshots = lineSnapshots.findLines(salesOrderId);
        if (snapshots.isEmpty()) {
            throw new IllegalStateException(
                "No lines found for sales_order_header_id=" + salesOrderId + " — cannot request reservation"
            );
        }

        List<StockReservationRequested.RequestedLine> lines = new ArrayList<>();
        for (LineSnapshot s : snapshots) {
            lines.add(new StockReservationRequested.RequestedLine(
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
            DEFAULT_WAREHOUSE,
            lines,
            Instant.now()
        );
        appendOutbox(event, "SalesOrderFulfilmentSaga", event.aggregateId());

        saga.transitionTo(STOCK_RESERVATION_REQUESTED, "wait_for_stock_reserved");
        saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} sales_order={} → stock_reservation_requested ({} line(s))",
            workerId, saga.sagaId(), salesOrderId, lines.size());
    }

    private void requestManufacturing(SalesOrderFulfilmentSaga saga) {
        UUID salesOrderId = saga.salesOrderId();
        // hasShortage preserves the empty-means-no-shortage semantic from
        // StockReservedHandler.extractShortage. applyStockReserved routes
        // RESERVED outcomes straight to READY_TO_SHIP, so STOCK_RESERVED is
        // only reachable for partial / failed reservations — which always
        // carry a non-empty shortage map. An empty map here is therefore a
        // "shouldn't happen" defensive case: skip + WARN to surface the
        // invariant violation rather than emit a ManufacturingRequested
        // that would over-produce.
        Map<Integer, BigDecimal> shortageByLineNumber = readShortage(saga);
        boolean hasShortage = !shortageByLineNumber.isEmpty();

        if (!hasShortage) {
            log.warn("[{}] saga {} sales_order={} reached STOCK_RESERVED with no shortage stashed; skipping ManufacturingRequested emission. applyStockReserved should have routed full-reservation outcomes to READY_TO_SHIP.",
                workerId, saga.sagaId(), salesOrderId);
            return;
        }

        List<ManufacturingRequested.RequestedLine> lines = new ArrayList<>();
        for (LineSnapshot s : lineSnapshots.findLines(salesOrderId)) {
            BigDecimal shortage = shortageByLineNumber.get(s.lineNumber());
            if (shortage == null || shortage.signum() <= 0) {
                continue;
            }
            lines.add(new ManufacturingRequested.RequestedLine(
                s.salesOrderLineId(),
                s.lineNumber(),
                s.productId(),
                s.productSku(),
                s.productName(),
                shortage
            ));
        }

        if (lines.isEmpty()) {
            saga.transitionTo(MANUFACTURING_REQUESTED, "wait_for_work_order_created");
            saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));
            log.info("[{}] saga {} sales_order={} → manufacturing_requested (no demand to forward)",
                workerId, saga.sagaId(), salesOrderId);
            return;
        }

        ManufacturingRequested event = new ManufacturingRequested(
            UUID.randomUUID(),
            salesOrderId,
            salesOrderId,
            lines,
            Instant.now()
        );
        appendOutbox(event, "SalesOrderFulfilmentSaga", event.aggregateId());

        saga.transitionTo(MANUFACTURING_REQUESTED, "wait_for_work_order_created");
        saga.parkUntil(Instant.now().plus(Duration.ofDays(1)));

        log.info("[{}] saga {} sales_order={} → manufacturing_requested ({} line(s), shortage forwarded)",
            workerId, saga.sagaId(), salesOrderId, lines.size());
    }

    private Map<Integer, BigDecimal> readShortage(SalesOrderFulfilmentSaga saga) {
        String raw = saga.dataJson();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return Map.of();
        }
        try {
            FulfilmentSagaData data = json.readValue(raw, FulfilmentSagaData.class);
            return data == null || data.shortageByLineNumber() == null
                ? Map.of()
                : data.shortageByLineNumber();
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot parse saga.data: " + raw, e);
        }
    }

    private void appendOutbox(DomainEvent event, String aggregateType, UUID aggregateId) {
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                aggregateType,
                aggregateId,
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                null, null, null,
                null
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise " + event.eventType(), e);
        }
    }
}
