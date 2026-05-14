package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.manufacturing.domain.events.RawMaterialReservationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code manufacturing.RawMaterialReservationRequested}.
 * Delegates the actual reservation to
 * {@link StockReservationService#reserveForWorkOrder}, which performs the
 * {@code stock_balance} mutations, persists the reservation, and emits
 * {@code inventory.RawMaterialsReserved} via the outbox — all in the same
 * transaction.
 */
@Component
public class RawMaterialReservationRequestedHandler extends AbstractInboxHandler<RawMaterialReservationRequested> {

    public static final String CONSUMER_NAME = "inventory.raw-material-reservation";

    private final StockReservationService reservation;

    public RawMaterialReservationRequestedHandler(
        InboxPort inbox,
        StockReservationService reservation,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialReservationRequested.class, RawMaterialReservationRequested.EVENT_TYPE, CONSUMER_NAME);
        this.reservation = reservation;
    }

    @Override
    protected void apply(RawMaterialReservationRequested payload, EventEnvelope envelope) {
        reservation.reserveForWorkOrder(payload);

        log.info("[{}] processed {} ({}) for work_order={}",
            CONSUMER_NAME, envelope.eventType(), envelope.eventId(), payload.workOrderId());
    }
}
