package com.northwood.manufacturing.application.inbox;

import com.northwood.inventory.domain.events.OrderPeggedSupplyCancellationRequested;
import com.northwood.manufacturing.application.WorkOrderCancellationService;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.events.WorkOrderCancellationApplied;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.OrderPeggedSupplyCancellationRequested} — the
 * manufacturing leg of multi-leg sales-order compensation. Filters on
 * {@code targetService = "manufacturing"} (purchasing has its own sibling handler).
 *
 * <p>Withdraws the order-pegged work order via
 * {@link WorkOrderCancellationService#compensateCancel} (RELEASED → cancelled +
 * raw materials released; in-progress → refused) and emits
 * {@code manufacturing.WorkOrderCancellationApplied} back to the sales saga, with
 * {@code compensated} reflecting whether the WO was withdrawn. The cancel is
 * effect-gated on the WO's own status inside {@code compensateCancel} (idempotent on
 * an already-cancelled WO), so a redelivered request is a no-op that still re-acks.
 */
@Component
public class OrderPeggedSupplyCancellationRequestedHandler
    extends AbstractInboxHandler<OrderPeggedSupplyCancellationRequested> {

    public static final String HANDLER_NAME = "manufacturing.order-pegged-supply-cancel";

    private final WorkOrderCancellationService workOrderCancellation;
    private final OutboxAppender outbox;

    public OrderPeggedSupplyCancellationRequestedHandler(
        InboxPort inbox,
        WorkOrderCancellationService workOrderCancellation,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json, OrderPeggedSupplyCancellationRequested.class,
            OrderPeggedSupplyCancellationRequested.EVENT_TYPE, HANDLER_NAME);
        this.workOrderCancellation = workOrderCancellation;
        this.outbox = outbox;
    }

    @Override
    protected void apply(OrderPeggedSupplyCancellationRequested payload, EventEnvelope envelope) {
        if (!OrderPeggedSupplyCancellationRequested.TARGET_SERVICE_MANUFACTURING.equals(payload.targetService())) {
            log.debug("[{}] skipping {} ({}) — targetService={} routes to a different service",
                HANDLER_NAME, envelope.eventType(), envelope.eventId(), payload.targetService());
            return;
        }

        UUID workOrderId = payload.targetAggregateId();
        WorkOrderCancellationService.CompensationResult result = workOrderCancellation.compensateCancel(
            workOrderId,
            "sales order " + payload.sourceSalesOrderHeaderId()
                + " cancelled — withdrawing order-pegged work order");

        outbox.append(new WorkOrderCancellationApplied(
            UUID.randomUUID(),
            workOrderId,
            payload.sourceSalesOrderHeaderId(),
            payload.sourceSalesOrderLineId(),
            result.compensated(),
            result.previousStatus(),
            result.detail(),
            Instant.now()
        ), WorkOrder.AGGREGATE_TYPE, envelope.actorUserId());

        log.info("[{}] processed {} ({}) for work_order={} sales_order={} → compensated={}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(), workOrderId,
            payload.sourceSalesOrderHeaderId(), result.compensated());
    }
}
