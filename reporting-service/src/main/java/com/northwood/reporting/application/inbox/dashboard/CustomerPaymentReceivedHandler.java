package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.finance.domain.events.CustomerPaymentReceived;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("dashboard_CustomerPaymentReceivedHandler")
public class CustomerPaymentReceivedHandler extends AbstractInboxHandler<CustomerPaymentReceived> {

    public static final String HANDLER_NAME = "reporting.dashboard.customer-payment-received";

    private final FinancialDashboardProjection projection;

    public CustomerPaymentReceivedHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerPaymentReceived.class, CustomerPaymentReceived.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(CustomerPaymentReceived payload, EventEnvelope envelope) {
        projection.recordCustomerPayment(payload.allocatedAmount(), payload.currencyCode(), payload.occurredAt());
    }
}
