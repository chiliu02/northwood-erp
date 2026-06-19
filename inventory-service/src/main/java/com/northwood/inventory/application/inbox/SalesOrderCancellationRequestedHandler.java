package com.northwood.inventory.application.inbox;

import com.northwood.inventory.application.StockReservationService;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code sales.SalesOrderCancellationRequested}: the inventory
 * side of the cancel-vs-ship arbitration. It first tries to claim cancellation on
 * {@code sales_order_line_facts} (the same rows the ship-claim row-locks):
 *
 * <ul>
 *   <li><b>Applied</b> (no line has shipped) — releases the stock reservation and
 *       emits {@code inventory.SalesOrderCancellationApplied}, which lets sales
 *       finalise the order to {@code cancelled} and the fulfilment saga reach
 *       {@code compensated}.</li>
 *   <li><b>Rejected</b> (a shipment beat the cancel) — does nothing: the goods have
 *       left, so the reservation is already consumed and the order must proceed as
 *       shipped. No ack is emitted, so sales never confirms the cancellation and the
 *       order rides the normal ship → invoice → pay path.</li>
 * </ul>
 *
 * <p>This is what makes the cancel two-phase: sales only <em>requests</em>
 * cancellation; inventory's claim decides, synchronously serialized against any
 * concurrent shipment, so the order is never both shipped and cancelled.
 */
@Component
public class SalesOrderCancellationRequestedHandler extends AbstractInboxHandler<SalesOrderCancellationRequested> {

    public static final String HANDLER_NAME = "inventory.sales-cancel";

    private final StockReservationService reservation;
    private final SalesOrderLineFactsProjection salesOrderLineFacts;

    public SalesOrderCancellationRequestedHandler(
        InboxPort inbox,
        StockReservationService reservation,
        SalesOrderLineFactsProjection salesOrderLineFacts,
        ObjectMapper json
    ) {
        super(inbox, json, SalesOrderCancellationRequested.class, SalesOrderCancellationRequested.EVENT_TYPE, HANDLER_NAME);
        this.reservation = reservation;
        this.salesOrderLineFacts = salesOrderLineFacts;
    }

    @Override
    protected void apply(SalesOrderCancellationRequested payload, EventEnvelope envelope) {
        boolean applied = salesOrderLineFacts.tryClaimCancellation(payload.aggregateId());
        if (applied) {
            // releaseForSalesOrder also emits InventorySalesOrderCancellationApplied.
            reservation.releaseForSalesOrder(payload.aggregateId());
            log.info("[{}] cancellation applied for sales_order={} ({})",
                HANDLER_NAME, payload.aggregateId(), envelope.eventId());
        } else {
            log.info("[{}] cancellation REJECTED for sales_order={} — a line already shipped; order proceeds as shipped ({})",
                HANDLER_NAME, payload.aggregateId(), envelope.eventId());
        }
    }
}
