package com.northwood.sales.application.inbox;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code finance.CustomerInvoiceCreated}. Asks the manager
 * to advance {@code goods_shipped → invoice_created}.
 */
@Component
public class CustomerInvoiceCreatedHandler extends AbstractInboxHandler<CustomerInvoiceCreated> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.customer-invoice-created";

    private final SalesOrderFulfilmentSagaManager sagaManager;

    public CustomerInvoiceCreatedHandler(
        InboxPort inbox, SalesOrderFulfilmentSagaManager sagaManager, ObjectMapper json
    ) {
        super(inbox, json, CustomerInvoiceCreated.class, CustomerInvoiceCreated.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
    }

    @Override
    protected void apply(CustomerInvoiceCreated payload, EventEnvelope envelope) {
        sagaManager.applyCustomerInvoiceCreated(payload.salesOrderHeaderId());
    }
}
