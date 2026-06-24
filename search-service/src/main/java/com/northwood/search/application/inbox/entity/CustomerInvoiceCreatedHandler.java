package com.northwood.search.application.inbox.entity;

import com.northwood.finance.domain.events.CustomerInvoiceCreated;
import com.northwood.search.application.GlobalEntityProjection;
import com.northwood.search.application.inbox.AbstractEventProjectionHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Indexes a customer invoice into the global {@code entities} index (read model B). */
@Component
public class CustomerInvoiceCreatedHandler extends AbstractEventProjectionHandler<CustomerInvoiceCreated> {

    public static final String HANDLER_NAME = "search.entities.customer-invoice-created";

    private final GlobalEntityProjection entities;

    public CustomerInvoiceCreatedHandler(ObjectMapper json, GlobalEntityProjection entities) {
        super(json, CustomerInvoiceCreated.class, CustomerInvoiceCreated.EVENT_TYPE, HANDLER_NAME);
        this.entities = entities;
    }

    @Override
    protected void apply(CustomerInvoiceCreated payload, EventEnvelope envelope) {
        entities.upsertEntity(
            GlobalEntityProjection.TYPE_CUSTOMER_INVOICE, payload.aggregateId(),
            payload.invoiceNumber(), payload.invoiceNumber(), payload.customerName(), payload.status());
    }
}
