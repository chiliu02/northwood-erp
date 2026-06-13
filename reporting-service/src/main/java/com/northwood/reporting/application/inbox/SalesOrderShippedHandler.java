package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.sales.domain.events.SalesOrderShipped;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

/**
 * Reporting 360-view handler for {@code sales.SalesOrderShipped}. Drives
 * {@code shipment_status} ({@code partially_shipped} vs {@code shipped}) and the
 * shipment-driven {@code order_status} advance off {@code orderFullyShipped} —
 * which only sales can compute (it owns ordered vs. cumulative-shipped quantity).
 *
 * <p>Replaces the former {@code inventory.ShipmentPosted}-driven handler: that
 * event can't distinguish a partial shipment from a full one, so it always
 * reported {@code 'shipped'}. {@code SalesOrderShipped} is emitted once per
 * shipment carrying the order-level flag.
 */
@Component
public class SalesOrderShippedHandler extends AbstractInboxHandler<SalesOrderShipped> {

    public static final String HANDLER_NAME = "reporting.sales-order-360.sales-order-shipped";

    private final SalesOrder360Projection projection;

    public SalesOrderShippedHandler(
        InboxPort inbox,
        SalesOrder360Projection projection,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderShipped.class, SalesOrderShipped.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(SalesOrderShipped payload, EventEnvelope envelope) {
        projection.recordShipment(
            payload.aggregateId(), payload.orderFullyShipped(), payload.occurredAt(), envelope.actorUserId());
    }
}
