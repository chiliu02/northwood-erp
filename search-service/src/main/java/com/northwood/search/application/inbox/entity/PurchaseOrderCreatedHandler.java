package com.northwood.search.application.inbox.entity;

import com.northwood.purchasing.domain.events.PurchaseOrderCreated;
import com.northwood.search.application.GlobalEntityProjection;
import com.northwood.search.application.inbox.AbstractEventProjectionHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Indexes a purchase order into the global {@code entities} index (read model B). */
@Component
public class PurchaseOrderCreatedHandler extends AbstractEventProjectionHandler<PurchaseOrderCreated> {

    public static final String HANDLER_NAME = "search.entities.purchase-order-created";

    private final GlobalEntityProjection entities;

    public PurchaseOrderCreatedHandler(ObjectMapper json, GlobalEntityProjection entities) {
        super(json, PurchaseOrderCreated.class, PurchaseOrderCreated.EVENT_TYPE, HANDLER_NAME);
        this.entities = entities;
    }

    @Override
    protected void apply(PurchaseOrderCreated payload, EventEnvelope envelope) {
        entities.upsertEntity(
            GlobalEntityProjection.TYPE_PURCHASE_ORDER, payload.aggregateId(),
            payload.purchaseOrderNumber(), payload.purchaseOrderNumber(), payload.supplierName(), payload.status());
    }
}
