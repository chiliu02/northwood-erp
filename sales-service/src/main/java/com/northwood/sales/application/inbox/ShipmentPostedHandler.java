package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPLETED;

import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.inventory.domain.events.ShipmentPosted;
import com.northwood.sales.domain.SalesOrder;
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
 * Inbox handler for {@code inventory.ShipmentPosted}. Records the shipment on
 * the {@code SalesOrder} aggregate FIRST (every shipment — accumulates per-line
 * shipped quantity, moves the header to {@code shipped} / {@code partially_shipped},
 * and emits {@code SalesOrderShipped} → finance's per-shipment invoice), then
 * advances the fulfilment saga gated on whether this shipment completed the
 * order ({@link SalesOrder.ShipmentOutcome#orderFullyShipped()}): a partial
 * shipment parks the saga at {@code partially_shipped}; the completing shipment
 * moves it to {@code goods_shipped}.
 *
 * <p>Inbox dedup short-circuits redelivery upstream, so "every shipment" means
 * every distinct shipment — one {@code SalesOrderShipped} (hence one invoice)
 * per shipment.
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
        List<ShippedLineInput> shippedLines = new ArrayList<>();
        for (ShipmentPosted.ShippedLine sl : payload.lines()) {
            shippedLines.add(new ShippedLineInput(
                sl.salesOrderLineId(), sl.productId(), sl.productSku(), sl.productName(),
                sl.shippedQuantity(), sl.unitCost()
            ));
        }
        SalesOrder.ShipmentOutcome outcome = salesOrders.recordShipped(
            payload.salesOrderHeaderId(),
            payload.aggregateId(),
            payload.shipmentNumber(),
            LocalDate.now(),
            shippedLines
        );
        String newState = sagaManager.applyShipmentPosted(
            payload.salesOrderHeaderId(), outcome.orderFullyShipped());
        // Prepayment + COD complete the saga at shipment, so complete the order
        // here (the payment-received handler that normally does it never fires the
        // completing transition for them). on_shipment orders get their shipped /
        // partially_shipped header from recordShipped → save(); their completion
        // comes later via CustomerPaymentReceivedHandler.
        if (COMPLETED.equals(newState)) {
            salesOrders.completeOrder(payload.salesOrderHeaderId());
        }
        log.info("[{}] sales_order={} → {} (shipment={}, orderFullyShipped={})",
            CONSUMER_NAME, payload.salesOrderHeaderId(), newState, payload.shipmentNumber(),
            outcome.orderFullyShipped());
    }
}
