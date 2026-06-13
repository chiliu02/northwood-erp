package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.STOCK_RESERVATION_REQUESTED;

import com.northwood.inventory.domain.WarehouseCodes;
import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort.LineSnapshot;
import com.northwood.sales.domain.events.StockReservationRequested;
import com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sales-side fan-in for {@code inventory.ReplenishmentFulfilled}.
 *
 * <p>Only fires when the event carries the sales-order back-reference fields
 * ({@code sourceSalesOrderHeaderId} + {@code sourceSalesOrderLineId} both
 * non-null) — fulfilments triggered by reorder-point breach or WO-shortage
 * carry null back-references and are silently ignored here (the replenishment
 * history consumers in reporting handle them).
 *
 * <p>Delegates the state machine work to
 * {@link SalesOrderFulfilmentSagaManager#applyReplenishmentFulfilled(UUID, UUID, boolean)}.
 * When the manager returns {@code stock_reservation_requested} (every
 * outstanding line replenished and at least one was a shortage top-up), this
 * handler emits a fresh {@code sales.StockReservationRequested} so inventory
 * will retry the reservation against the now-restocked inventory (mirrors the
 * work-order retry pattern — {@code reserveForSalesOrder} cancels the prior
 * partial reservation first). When all lines were order-pegged completions,
 * the manager returns {@code ready_to_ship} instead — the output was
 * already reserved on completion, so no retry is emitted.
 *
 * <p>Idempotent against duplicate deliveries: the manager treats a missing
 * line id (already removed from the outstanding set) as a no-op.
 */
@Component
public class ReplenishmentFulfilledHandler extends AbstractInboxHandler<ReplenishmentFulfilled> {

    public static final String HANDLER_NAME = "sales.fulfilment-saga.replenishment-fulfilled";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderLineSnapshotPort lineSnapshots;
    private final OutboxAppender outbox;

    public ReplenishmentFulfilledHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderLineSnapshotPort lineSnapshots,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json,
            ReplenishmentFulfilled.class,
            ReplenishmentFulfilled.EVENT_TYPE,
            HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.lineSnapshots = lineSnapshots;
        this.outbox = outbox;
    }

    @Override
    protected void apply(ReplenishmentFulfilled payload, EventEnvelope envelope) {
        UUID salesOrderHeaderId = payload.sourceSalesOrderHeaderId();
        UUID salesOrderLineId = payload.sourceSalesOrderLineId();
        if (salesOrderHeaderId == null || salesOrderLineId == null) {
            log.debug("[{}] {} ({}) carries null sales-order back-reference — not a SO-shortage replenishment, skipping",
                HANDLER_NAME, envelope.eventType(), envelope.eventId());
            return;
        }

        String newState = sagaManager.applyReplenishmentFulfilled(
            salesOrderHeaderId, salesOrderLineId, payload.pegged());
        if (STOCK_RESERVATION_REQUESTED.equals(newState)) {
            // At least one outstanding line was a shortage top-up — re-emit
            // StockReservationRequested so inventory tries the reservation again
            // against the now-restocked pool. inventory.StockReservationService
            // .reserveForSalesOrder drops any prior partial reservation first
            // (mirrors the work-order retry pattern). Order-pegged completions
            // instead return ready_to_ship — already reserved on
            // completion, no retry needed.
            emitRetryStockReservation(salesOrderHeaderId);
            log.info("[{}] sales_order={} → stock_reservation_requested; re-emitting {} to retry reservation",
                HANDLER_NAME, salesOrderHeaderId, StockReservationRequested.EVENT_TYPE);
        }
    }

    private void emitRetryStockReservation(UUID salesOrderHeaderId) {
        List<LineSnapshot> snapshots = lineSnapshots.findLines(salesOrderHeaderId);
        if (snapshots.isEmpty()) {
            log.warn("[{}] sales_order={} has no line snapshots; cannot re-emit StockReservationRequested",
                HANDLER_NAME, salesOrderHeaderId);
            return;
        }
        List<StockReservationRequested.RequestedLine> requestedLines = new ArrayList<>();
        for (LineSnapshot s : snapshots) {
            requestedLines.add(new StockReservationRequested.RequestedLine(
                s.salesOrderLineId(),
                s.lineNumber(),
                s.productId(),
                s.productSku(),
                s.productName(),
                s.orderedQuantity(),
                s.pegged()
            ));
        }
        outbox.append(new StockReservationRequested(
            UUID.randomUUID(),
            salesOrderHeaderId,
            salesOrderHeaderId,
            WarehouseCodes.MAIN,
            requestedLines,
            Instant.now()
        ), SalesOrderFulfilmentSaga.AGGREGATE_TYPE);
    }
}
