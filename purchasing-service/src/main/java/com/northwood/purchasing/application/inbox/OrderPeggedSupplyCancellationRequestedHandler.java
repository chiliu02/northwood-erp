package com.northwood.purchasing.application.inbox;

import com.northwood.inventory.domain.events.OrderPeggedSupplyCancellationRequested;
import com.northwood.purchasing.application.PurchaseOrderService;
import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.events.PurchaseOrderCancellationApplied;
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
 * purchasing leg of multi-leg sales-order compensation. Filters on
 * {@code targetService = "purchasing"} (manufacturing has its own sibling handler).
 *
 * <p>Withdraws the order-pegged PO via
 * {@link PurchaseOrderService#compensateCancel} (DRAFT/SENT → cancelled; goods
 * already received → refused) and emits
 * {@code purchasing.PurchaseOrderCancellationApplied} back to the sales saga, with
 * {@code compensated} reflecting whether the PO was withdrawn. The cancel is
 * effect-gated on the PO's own status inside {@code compensateCancel} (idempotent on
 * an already-cancelled PO), so a redelivered request is a no-op that still re-acks.
 */
@Component
public class OrderPeggedSupplyCancellationRequestedHandler
    extends AbstractInboxHandler<OrderPeggedSupplyCancellationRequested> {

    public static final String HANDLER_NAME = "purchasing.order-pegged-supply-cancel";

    private final PurchaseOrderService purchaseOrders;
    private final OutboxAppender outbox;

    public OrderPeggedSupplyCancellationRequestedHandler(
        InboxPort inbox,
        PurchaseOrderService purchaseOrders,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json, OrderPeggedSupplyCancellationRequested.class,
            OrderPeggedSupplyCancellationRequested.EVENT_TYPE, HANDLER_NAME);
        this.purchaseOrders = purchaseOrders;
        this.outbox = outbox;
    }

    @Override
    protected void apply(OrderPeggedSupplyCancellationRequested payload, EventEnvelope envelope) {
        if (!OrderPeggedSupplyCancellationRequested.TARGET_SERVICE_PURCHASING.equals(payload.targetService())) {
            log.debug("[{}] skipping {} ({}) — targetService={} routes to a different service",
                HANDLER_NAME, envelope.eventType(), envelope.eventId(), payload.targetService());
            return;
        }

        UUID purchaseOrderId = payload.targetAggregateId();
        PurchaseOrderService.CompensationResult result = purchaseOrders.compensateCancel(
            purchaseOrderId,
            "sales order " + payload.sourceSalesOrderHeaderId()
                + " cancelled — withdrawing order-pegged purchase order");

        outbox.append(new PurchaseOrderCancellationApplied(
            UUID.randomUUID(),
            purchaseOrderId,
            payload.sourceSalesOrderHeaderId(),
            payload.sourceSalesOrderLineId(),
            result.compensated(),
            result.previousStatus(),
            result.detail(),
            Instant.now()
        ), PurchaseOrder.AGGREGATE_TYPE, envelope.actorUserId());

        log.info("[{}] processed {} ({}) for purchase_order={} sales_order={} → compensated={}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(), purchaseOrderId,
            payload.sourceSalesOrderHeaderId(), result.compensated());
    }
}
