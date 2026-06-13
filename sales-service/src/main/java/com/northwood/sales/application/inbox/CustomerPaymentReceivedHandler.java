package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPLETED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.PREPAID;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.SalesOrderUpfrontPaymentSettledEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code finance.CustomerPaymentReceived}. Asks the manager
 * to apply the payment outcome; if the saga reaches {@code 'completed'},
 * completes the order on the aggregate ({@link SalesOrderService#completeOrder}
 * — a guarded transition, replacing the former blind
 * {@code markStatus(COMPLETED)}). If the saga reaches {@code 'prepaid'} (full
 * settlement of a prepayment invoice), emit
 * {@code sales.SalesOrderPrepaymentSettled} so inventory can flip the
 * shipment-gate flag.
 */
@Component
public class CustomerPaymentReceivedHandler extends AbstractInboxHandler<CustomerPaymentReceived> {

    public static final String HANDLER_NAME = "sales.fulfilment-saga.customer-payment-received";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderService salesOrders;
    private final SalesOrderUpfrontPaymentSettledEmitter upfrontSettledEmitter;

    public CustomerPaymentReceivedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderService salesOrders,
        SalesOrderUpfrontPaymentSettledEmitter upfrontSettledEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerPaymentReceived.class, CustomerPaymentReceived.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.salesOrders = salesOrders;
        this.upfrontSettledEmitter = upfrontSettledEmitter;
    }

    @Override
    protected void apply(CustomerPaymentReceived payload, EventEnvelope envelope) {
        // invoiceFullySettled: this one invoice is paid (drives prepayment/deposit,
        // which are single-invoice). orderFullySettled: every invoice for the order
        // is paid (drives on_shipment completion — a partially-shipped order has
        // several per-shipment invoices, so paying one must not complete the order).
        boolean invoiceFullySettled = CustomerPaymentReceived.INVOICE_STATUS_PAID.equals(payload.invoiceStatusAfter());
        String newState = sagaManager.applyCustomerPaymentReceived(
            payload.salesOrderHeaderId(), invoiceFullySettled, payload.orderFullySettled());
        if (COMPLETED.equals(newState)) {
            salesOrders.completeOrder(payload.salesOrderHeaderId());
        } else if (PREPAID.equals(newState)) {
            // The up-front payment (prepayment in full, or a deposit) just settled
            // → tell inventory to lift the shipment gate.
            upfrontSettledEmitter.emitUpfrontPaymentSettled(payload.salesOrderHeaderId());
        }
    }
}
