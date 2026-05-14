package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class CustomerInvoiceCreatedHandler extends AbstractInboxHandler<CustomerInvoiceCreated> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.invoice-created";

    private final SalesOrder360Projection projection;

    public CustomerInvoiceCreatedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerInvoiceCreated.class, CustomerInvoiceCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(CustomerInvoiceCreated payload, EventEnvelope envelope) {
        projection.recordInvoice(payload.salesOrderHeaderId(), payload.totalAmount(), payload.occurredAt(), envelope.actorUserId());
    }
}
