package com.northwood.finance.application.inbox;

import com.northwood.sales.domain.events.CustomerDeactivated;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * §1F.3: AR consumer of {@code sales.CustomerDeactivated}. Flags every
 * outstanding {@code finance.customer_invoice_header} row for the customer
 * with {@code flagged_for_collections = true} so a future collections UI /
 * workflow can pick them up.
 */
@Component
public class CustomerDeactivatedHandler extends AbstractInboxHandler<CustomerDeactivated> {

    public static final String CONSUMER_NAME = "finance.ar.customer-deactivated";

    private final CustomerInvoiceCollectionsProjection projection;

    public CustomerDeactivatedHandler(
        InboxPort inbox,
        CustomerInvoiceCollectionsProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, CustomerDeactivated.class, CustomerDeactivated.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(CustomerDeactivated payload, EventEnvelope envelope) {
        int flagged = projection.flagOutstandingForCollections(payload.aggregateId());
        log.info("[{}] applied {} ({}) for customer_id={} flagged={} invoice(s)",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(),
            payload.aggregateId(), flagged);
    }
}
