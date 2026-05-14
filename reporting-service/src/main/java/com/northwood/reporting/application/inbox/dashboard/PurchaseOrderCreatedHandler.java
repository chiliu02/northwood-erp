package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("dashboard_PurchaseOrderCreatedHandler")
public class PurchaseOrderCreatedHandler extends AbstractInboxHandler<PurchaseOrderCreated> {

    public static final String CONSUMER_NAME = "reporting.dashboard.po-created";

    private final FinancialDashboardProjection projection;

    public PurchaseOrderCreatedHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCreated.class, PurchaseOrderCreated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(PurchaseOrderCreated payload, EventEnvelope envelope) {
        projection.recordPurchaseOrderCreated(payload.currencyCode(), payload.occurredAt());
    }
}
