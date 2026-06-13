package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.reporting.application.inbox.SalesOrder360Projection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class CustomerPaymentReceivedHandler extends AbstractInboxHandler<CustomerPaymentReceived> {

    public static final String HANDLER_NAME = "reporting.sales-order-360.payment-received";

    private final SalesOrder360Projection projection;

    public CustomerPaymentReceivedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerPaymentReceived.class, CustomerPaymentReceived.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(CustomerPaymentReceived payload, EventEnvelope envelope) {
        projection.recordPayment(
            payload.salesOrderHeaderId(),
            payload.allocatedAmount(),
            payload.invoiceStatusAfter(),
            payload.occurredAt(),
            envelope.actorUserId()
        );
    }
}
