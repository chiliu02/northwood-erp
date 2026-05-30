package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.events.StockReserved;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

/**
 * Reporting handler for {@code inventory.StockReserved} that maintains the
 * SO-360 view's {@code stock_status}. Distinct from the {@code atp.*}
 * StockReserved handler, which feeds the available-to-promise read model — the
 * inbox dispatcher fans each event out to <em>every</em> matching handler, and
 * each dedups under its own consumer name.
 */
@Component
public class SalesOrderStockReservedHandler extends AbstractInboxHandler<StockReserved> {

    public static final String CONSUMER_NAME = "reporting.sales-order-360.stock-reserved";

    private final SalesOrder360Projection projection;

    public SalesOrderStockReservedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, StockReserved.class, StockReserved.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(StockReserved payload, EventEnvelope envelope) {
        projection.recordStockReserved(
            payload.salesOrderId(), payload.status(), payload.occurredAt(), envelope.actorUserId());
    }
}
