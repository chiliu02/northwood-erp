package com.northwood.search.application.inbox.entity;

import com.northwood.sales.domain.events.SalesOrderPlaced;
import com.northwood.search.application.GlobalEntityProjection;
import com.northwood.search.application.inbox.AbstractEventProjectionHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Indexes a sales order into the global {@code entities} index (read model B). */
@Component
public class SalesOrderPlacedHandler extends AbstractEventProjectionHandler<SalesOrderPlaced> {

    public static final String HANDLER_NAME = "search.entities.sales-order-placed";

    private final GlobalEntityProjection entities;

    public SalesOrderPlacedHandler(ObjectMapper json, GlobalEntityProjection entities) {
        super(json, SalesOrderPlaced.class, SalesOrderPlaced.EVENT_TYPE, HANDLER_NAME);
        this.entities = entities;
    }

    @Override
    protected void apply(SalesOrderPlaced payload, EventEnvelope envelope) {
        entities.upsertEntity(
            GlobalEntityProjection.TYPE_SALES_ORDER, payload.aggregateId(),
            payload.orderNumber(), payload.orderNumber(), payload.customerName(), "placed");
    }
}
