package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPLETED;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
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
 * projects the order header to {@code 'completed'}.
 */
@Component
public class CustomerPaymentReceivedHandler extends AbstractInboxHandler<CustomerPaymentReceived> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.customer-payment-received";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;

    public CustomerPaymentReceivedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerPaymentReceived.class, CustomerPaymentReceived.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
    }

    @Override
    protected void apply(CustomerPaymentReceived payload, EventEnvelope envelope) {
        boolean fullySettled = CustomerPaymentReceived.INVOICE_STATUS_PAID.equals(payload.invoiceStatusAfter());
        String newState = sagaManager.applyCustomerPaymentReceived(payload.salesOrderHeaderId(), fullySettled);
        if (COMPLETED.equals(newState)) {
            statusProjection.markStatus(payload.salesOrderHeaderId(), SalesOrder.Status.COMPLETED);
        }
    }
}
