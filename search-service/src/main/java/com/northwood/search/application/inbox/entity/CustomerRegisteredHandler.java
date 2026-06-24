package com.northwood.search.application.inbox.entity;

import com.northwood.sales.domain.events.CustomerRegistered;
import com.northwood.search.application.GlobalEntityProjection;
import com.northwood.search.application.inbox.AbstractEventProjectionHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Indexes a customer into the global {@code entities} index (read model B). */
@Component
public class CustomerRegisteredHandler extends AbstractEventProjectionHandler<CustomerRegistered> {

    public static final String HANDLER_NAME = "search.entities.customer-registered";

    private final GlobalEntityProjection entities;

    public CustomerRegisteredHandler(ObjectMapper json, GlobalEntityProjection entities) {
        super(json, CustomerRegistered.class, CustomerRegistered.EVENT_TYPE, HANDLER_NAME);
        this.entities = entities;
    }

    @Override
    protected void apply(CustomerRegistered payload, EventEnvelope envelope) {
        entities.upsertEntity(
            GlobalEntityProjection.TYPE_CUSTOMER, payload.aggregateId(),
            payload.name(), payload.customerCode(), payload.email(), "active");
    }
}
