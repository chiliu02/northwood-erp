package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.SUPPLY_SECURED;

import com.northwood.sales.application.SalesOrderReadyToShipEmitter;
import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort;
import com.northwood.sales.application.saga.SalesOrderLineSnapshotPort.LineSnapshot;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.StockReserved}. Resolves which sales-order
 * lines came back short, asks the manager to advance the saga, then records the
 * reservation outcome onto the {@code SalesOrder} aggregate — each line's
 * reserved quantity moves it onto the reservation band and the header fold
 * re-derives {@code 'reserved'} / {@code 'partially_reserved'} (the line
 * carries the authoritative in-progress band, replacing the former blind
 * {@code markStatus(IN_FULFILMENT)} projection write). When the manager reports
 * a full-reservation shortcut to {@code ready_to_ship}, emits
 * {@code sales.SalesOrderReadyToShip} so reporting can advance
 * {@code order_status} (the shipment picker's filter).
 *
 * <p>A partial/failed reservation no longer forwards anything to manufacturing —
 * inventory has already raised the {@code ReplenishmentRequest} for each short
 * line in the same transaction. This handler just hands the
 * short sales-order-line ids to the saga so it can park awaiting their
 * {@code ReplenishmentFulfilled} / {@code ReplenishmentCancelled}. The reply
 * carries per-line {@code lineNumber}; the line ids are resolved from the
 * order's line snapshots (positional line-number correlation, same as the rest
 * of the fulfilment flow).
 */
@Component
public class StockReservedHandler extends AbstractInboxHandler<StockReserved> {

    public static final String HANDLER_NAME = "sales.fulfilment-saga";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderService salesOrders;
    private final SalesOrderReadyToShipEmitter readyToShipEmitter;
    private final SalesOrderLineSnapshotPort lineSnapshots;

    public StockReservedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderService salesOrders,
        SalesOrderReadyToShipEmitter readyToShipEmitter,
        SalesOrderLineSnapshotPort lineSnapshots,
        ObjectMapper json
    ) {
        super(inbox, json, StockReserved.class, StockReserved.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.salesOrders = salesOrders;
        this.readyToShipEmitter = readyToShipEmitter;
        this.lineSnapshots = lineSnapshots;
    }

    @Override
    protected void apply(StockReserved payload, EventEnvelope envelope) {
        Set<UUID> shortageLineIds = extractShortageLineIds(payload);
        String newState = sagaManager.applyStockReserved(payload.salesOrderId(), payload.status(), shortageLineIds);
        salesOrders.recordReservation(payload.salesOrderId(), reservedByLineNumber(payload));

        // Full reservation shortcuts the saga straight to supply_secured. Emit so
        // reporting can advance order_status — the value the shipment picker
        // filters on. Partial/failed park at stock_reservation_incomplete and
        // wait for inventory's replenishment to fulfil.
        if (SUPPLY_SECURED.equals(newState)) {
            readyToShipEmitter.emitReadyToShip(payload.salesOrderId());
        }

        log.info("[{}] sales_order={} status={} saga_state={} (reservation_id={})",
            HANDLER_NAME, payload.salesOrderId(), payload.status(), newState, payload.stockReservationId());
    }

    /**
     * Per-line reserved quantity keyed by {@code line_number}, as inventory
     * reported it. Fed to {@link SalesOrderService#recordReservation} so the
     * aggregate moves each line onto the reservation band and the header fold
     * derives {@code reserved} / {@code partially_reserved}. Positional line-number correlation, the same
     * basis the rest of the fulfilment flow uses.
     */
    private static Map<Integer, BigDecimal> reservedByLineNumber(StockReserved payload) {
        Map<Integer, BigDecimal> reserved = new HashMap<>();
        for (StockReserved.ReservedLine line : payload.lines()) {
            reserved.put(line.lineNumber(), line.reservedQuantity());
        }
        return reserved;
    }

    /**
     * Sales-order-line ids of the lines the reservation came back short on.
     * Resolves the reply's per-line {@code lineNumber}s against the order's line
     * snapshots. Empty for a full reservation.
     */
    private Set<UUID> extractShortageLineIds(StockReserved payload) {
        Set<Integer> shortLineNumbers = new LinkedHashSet<>();
        for (StockReserved.ReservedLine line : payload.lines()) {
            if (line.shortageQuantity() != null && line.shortageQuantity().signum() > 0) {
                shortLineNumbers.add(line.lineNumber());
            }
        }
        if (shortLineNumbers.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (LineSnapshot s : lineSnapshots.findLines(payload.salesOrderId())) {
            if (shortLineNumbers.contains(s.lineNumber())) {
                ids.add(s.salesOrderLineId());
            }
        }
        return ids;
    }
}
