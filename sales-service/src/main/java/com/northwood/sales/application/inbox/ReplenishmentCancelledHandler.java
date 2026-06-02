package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.REJECTED;

import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.sales.domain.events.SalesOrderCancellationRequested;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.outbox.OutboxAppender;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.ReplenishmentCancelled}.
 * The failure counterpart of {@link ReplenishmentFulfilledHandler}: a short
 * sales-order line's replenishment couldn't be sourced (unsourceable SKU, no
 * active BOM, no approved vendor), so the order can't be fulfilled. The saga
 * transitions {@code stock_reservation_incomplete → rejected} (any one
 * un-fulfillable line rejects the whole order), the
 * order header flips to {@code rejected}, and {@code SalesOrderCancellationRequested}
 * is emitted so inventory releases any partial reservation.
 *
 * <p>Gates on {@code sourceSalesOrderHeaderId != null} — reorder-point /
 * work-order-shortage cancellations carry null back-references (no sales saga
 * to notify) and are skipped.
 */
@Component
public class ReplenishmentCancelledHandler extends AbstractInboxHandler<ReplenishmentCancelled> {

    public static final String CONSUMER_NAME = "sales.fulfilment-saga.replenishment-cancelled";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderHeaderStatusProjection statusProjection;
    private final SalesOrderRepository salesOrders;
    private final OutboxAppender outbox;

    public ReplenishmentCancelledHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderHeaderStatusProjection statusProjection,
        SalesOrderRepository salesOrders,
        OutboxAppender outbox,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentCancelled.class, ReplenishmentCancelled.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.statusProjection = statusProjection;
        this.salesOrders = salesOrders;
        this.outbox = outbox;
    }

    @Override
    protected void apply(ReplenishmentCancelled payload, EventEnvelope envelope) {
        UUID salesOrderHeaderId = payload.sourceSalesOrderHeaderId();
        UUID salesOrderLineId = payload.sourceSalesOrderLineId();
        if (salesOrderHeaderId == null || salesOrderLineId == null) {
            log.debug("[{}] {} ({}) carries null sales-order back-reference — not a SO-shortage replenishment, skipping",
                CONSUMER_NAME, envelope.eventType(), envelope.eventId());
            return;
        }

        String newState = sagaManager.applyReplenishmentCancelled(salesOrderHeaderId, salesOrderLineId, payload.reason());
        if (REJECTED.equals(newState)) {
            statusProjection.markStatus(salesOrderHeaderId, SalesOrder.Status.REJECTED);
            emitCancellationRequest(salesOrderHeaderId,
                "Replenishment for a short line could not be sourced: " + payload.reason());
            log.info("[{}] sales_order={} rejected (replenishment cancelled for line={}); compensation requested",
                CONSUMER_NAME, salesOrderHeaderId, salesOrderLineId);
        }
    }

    /**
     * Emit {@code sales.SalesOrderCancellationRequested} for a system-driven
     * rejection without going through {@link SalesOrder#cancel(String)}.
     * Inventory consumes it to release any partial stock reservation.
     *
     * <p><b>Silent-fallback contract.</b> Loads the order to populate
     * {@code orderNumber} + {@code customerId}. If it can't be loaded —
     * shouldn't happen, the saga exists for an existing order — we log WARN and
     * skip the emission rather than throw; the saga has already transitioned to
     * {@code rejected} (terminal), so even without downstream compensation it is
     * in a sensible state. (Mirrors the former
     * {@code ManufacturingDispatchedHandler.emitCancellationRequest}.)
     */
    private void emitCancellationRequest(UUID salesOrderHeaderId, String reason) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId)).orElse(null);
        if (order == null) {
            log.warn("emitCancellationRequest sales_order={} could not load SalesOrder; skipping emission. "
                + "Downstream compensation (stock release) will NOT fire.", salesOrderHeaderId);
            return;
        }
        outbox.append(new SalesOrderCancellationRequested(
            UUID.randomUUID(),
            salesOrderHeaderId,
            order.orderNumber(),
            order.customerId(),
            reason,
            Instant.now()
        ), SalesOrder.AGGREGATE_TYPE);
    }
}
