package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPLETED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.PREPAID;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.sales.application.SalesOrderPrepaymentSettledEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code finance.CustomerPaymentReceived}. Asks the manager
 * to apply the payment outcome; if the saga reaches {@code 'completed'},
 * projects the order header to {@code 'completed'}. §2.31 Slice C: if the
 * saga reaches {@code 'prepaid'} (full settlement of a prepayment invoice),
 * emit {@code sales.SalesOrderPrepaymentSettled} so inventory can flip the
 * shipment-gate flag.
 */
@Component
public class CustomerPaymentReceivedHandler extends AbstractInboxHandler<CustomerPaymentReceived> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.customer-payment-received";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;
    private final SalesOrderPrepaymentSettledEmitter prepaymentSettledEmitter;

    public CustomerPaymentReceivedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        SalesOrderPrepaymentSettledEmitter prepaymentSettledEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerPaymentReceived.class, CustomerPaymentReceived.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
        this.prepaymentSettledEmitter = prepaymentSettledEmitter;
    }

    @Override
    protected void apply(CustomerPaymentReceived payload, EventEnvelope envelope) {
        boolean fullySettled = CustomerPaymentReceived.INVOICE_STATUS_PAID.equals(payload.invoiceStatusAfter());
        String newState = sagaManager.applyCustomerPaymentReceived(payload.salesOrderHeaderId(), fullySettled);
        if (COMPLETED.equals(newState)) {
            statusProjection.markStatus(payload.salesOrderHeaderId(), SalesOrder.Status.COMPLETED);
        } else if (PREPAID.equals(newState)) {
            prepaymentSettledEmitter.emitPrepaymentSettled(payload.salesOrderHeaderId());
        }
    }
}
