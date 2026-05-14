package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.manufacturing.domain.events.WorkOrderCancelled;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.WorkOrderCancelled}: releases the
 * raw-material reservation held against the cancelled work order. Does not
 * emit an ack — manufacturing's own sales-cancel ack already covers the
 * saga-coordination side.
 */
@Component
public class WorkOrderCancelledHandler extends AbstractInboxHandler<WorkOrderCancelled> {

    public static final String CONSUMER_NAME = "inventory.work-order-cancelled";

    private final StockReservationService reservation;

    public WorkOrderCancelledHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCancelled.class, WorkOrderCancelled.EVENT_TYPE, CONSUMER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(WorkOrderCancelled payload, EventEnvelope envelope) {
        reservation.releaseForWorkOrder(payload.aggregateId());

        log.info("[{}] processed {} ({}) for work_order={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId());
    }
}
