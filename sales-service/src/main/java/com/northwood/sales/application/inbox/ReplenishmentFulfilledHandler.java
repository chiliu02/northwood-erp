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
 * §2.36 Slice E: sales-side fan-in for {@code inventory.ReplenishmentFulfilled}.
 *
 * <p>Only fires when the event carries the sales-order back-reference fields
 * ({@code sourceSalesOrderHeaderId} + {@code sourceSalesOrderLineId} both
 * non-null) — fulfilments triggered by reorder-point breach or WO-shortage
 * carry null back-references and are silently ignored here (the existing
 * §2.35 consumers in reporting handle them).
 *
 * <p>Delegates the state machine work to
 * {@link SalesOrderFulfilmentSagaManager#applyReplenishmentFulfilled(UUID, UUID)}.
 * When the manager returns {@code stock_reservation_requested} (every
 * outstanding purchasing-line has been replenished), this handler emits a
 * fresh {@code sales.StockReservationRequested} so inventory will retry the
 * reservation against the now-restocked inventory. The work the inventory
 * service does on that second emission mirrors the §2.9 work-order retry
 * pattern — {@code reserveForSalesOrder} cancels the prior partial
 * reservation before creating the new one.
 *
 * <p>Idempotent against duplicate deliveries: the manager treats a missing
 * line id (already removed from the outstanding set) as a no-op.
 */
@Component
public class ReplenishmentFulfilledHandler extends AbstractInboxHandler<ReplenishmentFulfilled> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.replenishment-fulfilled";

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
            CONSUMER_NAME);
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
                CONSUMER_NAME, envelope.eventType(), envelope.eventId());
            return;
        }

        String newState = sagaManager.applyReplenishmentFulfilled(salesOrderHeaderId, salesOrderLineId);
        if (STOCK_RESERVATION_REQUESTED.equals(newState)) {
            // All outstanding purchasing-line replenishments have landed — re-
            // emit StockReservationRequested so inventory tries the reservation
            // again. inventory.StockReservationService.reserveForSalesOrder
            // drops any prior partial reservation first (§2.36 mirrors the
            // §2.9 work-order retry pattern).
            emitRetryStockReservation(salesOrderHeaderId);
            log.info("[{}] sales_order={} → stock_reservation_requested; re-emitting {} to retry reservation",
                CONSUMER_NAME, salesOrderHeaderId, StockReservationRequested.EVENT_TYPE);
        }
    }

    private void emitRetryStockReservation(UUID salesOrderHeaderId) {
        List<LineSnapshot> snapshots = lineSnapshots.findLines(salesOrderHeaderId);
        if (snapshots.isEmpty()) {
            log.warn("[{}] sales_order={} has no line snapshots; cannot re-emit StockReservationRequested",
                CONSUMER_NAME, salesOrderHeaderId);
            return;
        }
        List<StockReservationRequested.RequestedLine> requestedLines = new ArrayList<>();
        for (LineSnapshot s : snapshots) {
            requestedLines.add(new StockReservationRequested.RequestedLine(
                s.lineNumber(),
                s.productId(),
                s.productSku(),
                s.productName(),
                s.orderedQuantity()
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
