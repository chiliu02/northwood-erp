package com.northwood.reporting.application.inbox.dashboard;

import com.northwood.reporting.application.inbox.CustomerDashboardProjection;
import com.northwood.sales.domain.events.CustomerDeactivated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Dashboard consumer of {@code sales.CustomerDeactivated}. Stamps
 * the customer's row in {@code reporting.customer_dashboard_status} to
 * {@code 'inactive'} so dashboard widgets stop counting them in active-
 * customer totals.
 */
@Component("dashboard_CustomerDeactivatedHandler")
public class CustomerDeactivatedHandler extends AbstractInboxHandler<CustomerDeactivated> {

    public static final String CONSUMER_NAME = "reporting.dashboard.customer-deactivated";

    private final CustomerDashboardProjection projection;

    public CustomerDeactivatedHandler(
        InboxPort inbox,
        CustomerDashboardProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerDeactivated.class, CustomerDeactivated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(CustomerDeactivated payload, EventEnvelope envelope) {
        projection.recordCustomerDeactivated(payload.aggregateId(), payload.occurredAt());
    }
}
