package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.finance.domain.events.SupplierInvoiceApproved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("dashboard_SupplierInvoiceApprovedHandler")
public class SupplierInvoiceApprovedHandler extends AbstractInboxHandler<SupplierInvoiceApproved> {

    public static final String CONSUMER_NAME = "reporting.dashboard.supplier-invoice-approved";

    private final FinancialDashboardProjection projection;

    public SupplierInvoiceApprovedHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierInvoiceApproved.class, SupplierInvoiceApproved.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SupplierInvoiceApproved payload, EventEnvelope envelope) {
        projection.recordSupplierInvoice(payload.totalAmount(), payload.currencyCode(), payload.occurredAt());
    }
}
