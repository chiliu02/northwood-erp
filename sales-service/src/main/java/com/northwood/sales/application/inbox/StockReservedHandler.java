package com.northwood.sales.application.inbox;

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
 * any StockReserved event means inventory has acted on the request).
 */
@Component
public class StockReservedHandler extends AbstractInboxHandler<StockReserved> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;

    public StockReservedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        ObjectMapper json
    ) {
        super(inbox, json, StockReserved.class, StockReserved.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
    }

    @Override
    protected void apply(StockReserved payload, EventEnvelope envelope) {
        Map<Integer, BigDecimal> shortage = extractShortage(payload);
        sagaManager.applyStockReserved(payload.salesOrderId(), payload.status(), shortage);
        statusProjection.markStatus(payload.salesOrderId(), SalesOrder.IN_FULFILMENT);

        log.info("[{}] sales_order={} status={} (reservation_id={})",
            CONSUMER_NAME, payload.salesOrderId(), payload.status(), payload.stockReservationId());
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
