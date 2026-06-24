package com.northwood.search.application.inbox.entity;

import com.northwood.purchasing.domain.events.SupplierRegistered;
import com.northwood.search.application.GlobalEntityProjection;
import com.northwood.search.application.inbox.AbstractEventProjectionHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Indexes a supplier into the global {@code entities} index (read model B). */
@Component
public class SupplierRegisteredHandler extends AbstractEventProjectionHandler<SupplierRegistered> {

    public static final String HANDLER_NAME = "search.entities.supplier-registered";

    private final GlobalEntityProjection entities;

    public SupplierRegisteredHandler(ObjectMapper json, GlobalEntityProjection entities) {
        super(json, SupplierRegistered.class, SupplierRegistered.EVENT_TYPE, HANDLER_NAME);
        this.entities = entities;
    }

    @Override
    protected void apply(SupplierRegistered payload, EventEnvelope envelope) {
        entities.upsertEntity(
            GlobalEntityProjection.TYPE_SUPPLIER, payload.aggregateId(),
            payload.name(), payload.supplierCode(), payload.email(), payload.status());
    }
}
