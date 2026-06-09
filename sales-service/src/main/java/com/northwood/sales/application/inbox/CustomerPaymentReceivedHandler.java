package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPLETED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.DEPOSIT_PAID;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.PREPAID;

import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.sales.application.SalesOrderUpfrontPaymentSettledEmitter;
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
 * projects the order header to {@code 'completed'}. If the saga reaches
 * {@code 'prepaid'} (full settlement of a prepayment invoice), emit
 * {@code sales.SalesOrderPrepaymentSettled} so inventory can flip the
 * shipment-gate flag.
 */
@Component
public class CustomerPaymentReceivedHandler extends AbstractInboxHandler<CustomerPaymentReceived> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.customer-payment-received";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;
    private final SalesOrderUpfrontPaymentSettledEmitter upfrontSettledEmitter;

    public CustomerPaymentReceivedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        SalesOrderUpfrontPaymentSettledEmitter upfrontSettledEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerPaymentReceived.class, CustomerPaymentReceived.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
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
            statusProjection.markStatus(payload.salesOrderHeaderId(), SalesOrder.Status.COMPLETED);
        } else if (PREPAID.equals(newState) || DEPOSIT_PAID.equals(newState)) {
            // prepaid + deposit_paid both settle the up-front payment
            // → tell inventory to lift the shipment gate.
            upfrontSettledEmitter.emitUpfrontPaymentSettled(payload.salesOrderHeaderId());
        }
    }
}
