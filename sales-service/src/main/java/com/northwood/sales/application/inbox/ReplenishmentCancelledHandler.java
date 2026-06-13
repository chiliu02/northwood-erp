package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.REJECTED;

import com.northwood.inventory.domain.events.ReplenishmentCancelled;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.ReplenishmentCancelled}.
 * The failure counterpart of {@link ReplenishmentFulfilledHandler}: a short
 * sales-order line's replenishment couldn't be sourced (unsourceable SKU, no
 * active BOM, no approved vendor), so the order can't be fulfilled. The saga
 * transitions {@code stock_reservation_incomplete → rejected} (any one
 * un-fulfillable line rejects the whole order), and the order is rejected on the
 * aggregate ({@link SalesOrder#reject} — a guarded transition that flips the
 * header to {@code rejected} and emits {@code SalesOrderCancellationRequested}
 * so inventory releases any partial reservation, draining via the repository's
 * outbox in the same transaction).
 *
 * <p>Gates on {@code sourceSalesOrderHeaderId != null} — reorder-point /
 * work-order-shortage cancellations carry null back-references (no sales saga
 * to notify) and are skipped.
 */
@Component
public class ReplenishmentCancelledHandler extends AbstractInboxHandler<ReplenishmentCancelled> {

    public static final String HANDLER_NAME = "sales.fulfilment-saga.replenishment-cancelled";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderRepository salesOrders;

    public ReplenishmentCancelledHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderRepository salesOrders,
        ObjectMapper json
    ) {
        super(inbox, json, ReplenishmentCancelled.class, ReplenishmentCancelled.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.salesOrders = salesOrders;
    }

    @Override
    protected void apply(ReplenishmentCancelled payload, EventEnvelope envelope) {
        UUID salesOrderHeaderId = payload.sourceSalesOrderHeaderId();
        UUID salesOrderLineId = payload.sourceSalesOrderLineId();
        if (salesOrderHeaderId == null || salesOrderLineId == null) {
            log.debug("[{}] {} ({}) carries null sales-order back-reference — not a SO-shortage replenishment, skipping",
                HANDLER_NAME, envelope.eventType(), envelope.eventId());
            return;
        }

        String newState = sagaManager.applyReplenishmentCancelled(salesOrderHeaderId, salesOrderLineId, payload.reason());
        if (REJECTED.equals(newState)) {
            rejectOrder(salesOrderHeaderId, salesOrderLineId, payload.reason());
        }
    }

    /**
     * Reject the order on the aggregate and let the repository drain its
     * {@code SalesOrderCancellationRequested} to the outbox (inventory releases
     * any partial reservation).
     *
     * <p><b>Silent-fallback contract.</b> If the order can't be loaded —
     * shouldn't happen, the saga exists for an existing order — we log WARN and
     * skip rather than throw; the saga has already transitioned to
     * {@code rejected} (terminal), so even without downstream compensation it is
     * in a sensible state.
     */
    private void rejectOrder(UUID salesOrderHeaderId, UUID salesOrderLineId, String reason) {
        SalesOrder order = salesOrders.findById(SalesOrderId.of(salesOrderHeaderId)).orElse(null);
        if (order == null) {
            log.warn("[{}] sales_order={} rejected by saga but could not load SalesOrder; skipping aggregate reject. "
                + "Downstream compensation (stock release) will NOT fire.", HANDLER_NAME, salesOrderHeaderId);
            return;
        }
        order.reject("Replenishment for a short line could not be sourced: " + reason);
        salesOrders.save(order);
        log.info("[{}] sales_order={} rejected (replenishment cancelled for line={}); compensation requested",
            HANDLER_NAME, salesOrderHeaderId, salesOrderLineId);
    }
}
