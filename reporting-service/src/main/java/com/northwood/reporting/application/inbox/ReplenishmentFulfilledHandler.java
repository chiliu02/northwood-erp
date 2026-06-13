package com.northwood.reporting.application.inbox;

import com.northwood.inventory.domain.events.ReplenishmentFulfilled;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reporting consumer of
 * {@code inventory.ReplenishmentFulfilled}. Flips the
 * {@code reporting.replenishment_history_view} row to {@code 'fulfilled'}
 * and stamps {@code fulfilled_at}. Terminal state on that projection.
 *
 * <p>Also closes the loop on the Sales Order 360 for an order-pegged
 * ({@code to_order}) fulfilment: inventory reserved the output to the SO line
 * atomically at goods-receipt / WO-completion, so the sales saga ships off the
 * peg WITHOUT a re-reservation — it never emits {@code SalesOrderReadyToShip}
 * and no fresh {@code inventory.StockReserved} arrives. This pegged event is
 * therefore the <b>only</b> signal that lifts the order's 360 row to
 * {@code ready_to_ship} (and its Stock lozenge out of {@code 'failed'}); for a
 * non-pegged shortage top-up the pool was merely restocked and the saga still
 * re-reserves, so we leave those to the normal StockReserved / ReadyToShip path.
 */
@Component
public class ReplenishmentFulfilledHandler extends AbstractInboxHandler<ReplenishmentFulfilled> {

    public static final String HANDLER_NAME = "reporting.replenishment-history.fulfilled";

    private final ReplenishmentHistoryProjection projection;
    private final SalesOrder360Projection salesOrders;

    public ReplenishmentFulfilledHandler(
        InboxPort inbox,
        ReplenishmentHistoryProjection projection,
        SalesOrder360Projection salesOrders,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentFulfilled.class, ReplenishmentFulfilled.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
        this.salesOrders = salesOrders;
    }

    @Override
    protected void apply(ReplenishmentFulfilled payload, EventEnvelope envelope) {
        projection.recordFulfilled(payload.aggregateId(), payload.occurredAt());

        if (payload.pegged() && payload.sourceSalesOrderHeaderId() != null) {
            salesOrders.recordReadyToShip(
                payload.sourceSalesOrderHeaderId(), payload.occurredAt(), envelope.actorUserId());
        }

        log.info("[{}] applied {} ({}) for replenishment_request={}{}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId(),
            payload.pegged() && payload.sourceSalesOrderHeaderId() != null
                ? " (pegged → sales_order=" + payload.sourceSalesOrderHeaderId() + " ready_to_ship)" : "");
    }
}
