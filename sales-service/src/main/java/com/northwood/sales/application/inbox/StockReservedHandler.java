package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.READY_TO_SHIP;

import com.northwood.sales.application.SalesOrderReadyToShipEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.StockReserved}. Parses the payload,
 * extracts the per-line shortage map, asks the manager to advance the saga,
 * then projects the order header to {@code 'in_fulfilment'} (always, since
 * any StockReserved event means inventory has acted on the request). When the
 * manager reports a full-reservation shortcut to {@code ready_to_ship}, emits
 * {@code sales.SalesOrderReadyToShip} so reporting can advance
 * {@code order_status} (the shipment picker's filter).
 */
@Component
public class StockReservedHandler extends AbstractInboxHandler<StockReserved> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;
    private final SalesOrderReadyToShipEmitter readyToShipEmitter;

    public StockReservedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        SalesOrderReadyToShipEmitter readyToShipEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, StockReserved.class, StockReserved.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
        this.readyToShipEmitter = readyToShipEmitter;
    }

    @Override
    protected void apply(StockReserved payload, EventEnvelope envelope) {
        Map<Integer, BigDecimal> shortage = extractShortage(payload);
        String newState = sagaManager.applyStockReserved(payload.salesOrderId(), payload.status(), shortage);
        statusProjection.markStatus(payload.salesOrderId(), SalesOrder.Status.IN_FULFILMENT);

        // Full reservation shortcuts the saga straight to ready_to_ship (no
        // manufacturing leg). Emit so reporting can advance order_status — the
        // value the shipment picker filters on. Partial/failed return
        // stock_reservation_incomplete and forward to manufacturing instead.
        if (READY_TO_SHIP.equals(newState)) {
            readyToShipEmitter.emitReadyToShip(payload.salesOrderId());
        }

        log.info("[{}] sales_order={} status={} saga_state={} (reservation_id={})",
            CONSUMER_NAME, payload.salesOrderId(), payload.status(), newState, payload.stockReservationId());
    }

    private static Map<Integer, BigDecimal> extractShortage(StockReserved payload) {
        Map<Integer, BigDecimal> shortage = new LinkedHashMap<>();
        for (StockReserved.ReservedLine line : payload.lines()) {
            BigDecimal qty = line.shortageQuantity();
            if (qty != null && qty.signum() > 0) {
                shortage.put(line.lineNumber(), qty);
            }
        }
        return shortage;
    }
}
