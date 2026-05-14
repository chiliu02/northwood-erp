package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.GOODS_SHIPPED;

import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.sales.domain.SalesOrder.ShippedLineInput;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.ShipmentPosted}. Asks the manager to
 * advance {@code ready_to_ship → goods_shipped}; if the saga did transition,
 * delegates to {@link SalesOrderService#recordShipped} which loads the
 * {@code SalesOrder} aggregate, calls {@code recordShipped(...)} (flips
 * header to {@code 'shipped'} + emits {@code SalesOrderShipped}), and saves.
 */
@Component
public class ShipmentPostedHandler extends AbstractInboxHandler<ShipmentPosted> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.shipment-posted";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderService salesOrders;

    public ShipmentPostedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderService salesOrders,
        ObjectMapper json
    ) {
        super(inbox, json, ShipmentPosted.class, ShipmentPosted.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.salesOrders = salesOrders;
    }

    @Override
    protected void apply(ShipmentPosted payload, EventEnvelope envelope) {
        String newState = sagaManager.applyShipmentPosted(payload.salesOrderHeaderId());
        if (GOODS_SHIPPED.equals(newState)) {
            List<ShippedLineInput> shippedLines = new ArrayList<>();
            for (ShipmentPosted.ShippedLine sl : payload.lines()) {
                shippedLines.add(new ShippedLineInput(
                    sl.salesOrderLineId(), sl.productId(), sl.productSku(), sl.productName(), sl.shippedQuantity()
                ));
            }
            salesOrders.recordShipped(
                payload.salesOrderHeaderId(),
                payload.aggregateId(),
                payload.shipmentNumber(),
                LocalDate.now(),
                shippedLines
            );
            log.info("[{}] sales_order={} → goods_shipped (shipment={})",
                CONSUMER_NAME, payload.salesOrderHeaderId(), payload.shipmentNumber());
        }
    }
}
