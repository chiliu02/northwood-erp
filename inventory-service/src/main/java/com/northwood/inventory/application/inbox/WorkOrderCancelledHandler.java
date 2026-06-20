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
 * withdrawn work order's reserved raw materials back to the free pool. A
 * {@code released} WO cancelled for order-pegged compensation may have already
 * reserved its raw materials (its lifecycle saga ran through
 * {@code raw_materials_reserved}); this hands them back via
 * {@link StockReservationService#releaseForWorkOrder}, which is idempotent against
 * a WO that never reserved.
 */
@Component
public class WorkOrderCancelledHandler extends AbstractInboxHandler<WorkOrderCancelled> {

    public static final String HANDLER_NAME = "inventory.work-order-cancelled";

    private final StockReservationService reservation;

    public WorkOrderCancelledHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCancelled.class, WorkOrderCancelled.EVENT_TYPE, HANDLER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(WorkOrderCancelled payload, EventEnvelope envelope) {
        reservation.releaseForWorkOrder(payload.aggregateId());
        log.info("[{}] released raw materials for cancelled work_order={} ({})",
            HANDLER_NAME, payload.aggregateId(), envelope.eventId());
    }
}
