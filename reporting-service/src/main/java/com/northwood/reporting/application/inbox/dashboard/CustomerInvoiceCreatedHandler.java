package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("dashboard_CustomerInvoiceCreatedHandler")
public class CustomerInvoiceCreatedHandler extends AbstractInboxHandler<CustomerInvoiceCreated> {

    public static final String HANDLER_NAME = "reporting.dashboard.customer-invoice-created";

    private final FinancialDashboardProjection projection;

    public CustomerInvoiceCreatedHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerInvoiceCreated.class, CustomerInvoiceCreated.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(CustomerInvoiceCreated payload, EventEnvelope envelope) {
        projection.recordCustomerInvoice(payload.totalAmount(), payload.currencyCode(), payload.occurredAt());
    }
}
