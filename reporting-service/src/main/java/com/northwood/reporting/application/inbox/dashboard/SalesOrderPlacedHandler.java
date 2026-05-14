package com.northwood.reporting.application.inbox.dashboard;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("dashboard_SalesOrderPlacedHandler")
public class SalesOrderPlacedHandler extends AbstractInboxHandler<SalesOrderPlaced> {

    public static final String CONSUMER_NAME = "reporting.dashboard.sales-order-placed";

    private final FinancialDashboardProjection projection;

    public SalesOrderPlacedHandler(
        InboxPort inbox,
        FinancialDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderPlaced.class, SalesOrderPlaced.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderPlaced payload, EventEnvelope envelope) {
        projection.recordSalesOrderPlaced(payload.currencyCode(), payload.occurredAt());
    }
}
