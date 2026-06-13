package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.finance.domain.events.SupplierPaymentMade;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("dashboard_SupplierPaymentMadeHandler")
public class SupplierPaymentMadeHandler extends AbstractInboxHandler<SupplierPaymentMade> {

    public static final String HANDLER_NAME = "reporting.dashboard.supplier-payment-made";

    private final FinancialDashboardProjection projection;

    public SupplierPaymentMadeHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SupplierPaymentMade.class, SupplierPaymentMade.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SupplierPaymentMade payload, EventEnvelope envelope) {
        projection.recordSupplierPayment(payload.allocatedAmount(), payload.currencyCode(), payload.occurredAt());
    }
}
