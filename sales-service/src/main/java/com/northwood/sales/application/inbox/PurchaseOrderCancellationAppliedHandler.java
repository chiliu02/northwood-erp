package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATION_FAILED;

import com.northwood.inventory.domain.events.OrderPeggedSupplyCancellationRequested;
import com.northwood.purchasing.domain.events.PurchaseOrderCancellationApplied;
import com.northwood.sales.application.SalesOrderCompensationEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code purchasing.PurchaseOrderCancellationApplied} — the
 * purchasing leg of multi-leg sales-order compensation. Drains the leg
 * ({@code purchasing:<salesOrderLineId>}) from the fulfilment saga's
 * outstanding-compensation set; {@code compensated = false} records the leg as an
 * un-compensatable leaf so the saga reaches {@code compensation_failed} instead of
 * {@code compensated}. When the drain empties the set, the saga reaches its
 * terminal and this handler emits the matching {@code sales.SalesOrderCompensated}
 * / {@code sales.SalesOrderCompensationFailed}.
 *
 * <p>The leg id is formed the same way inventory enumerated it on the
 * cancellation-applied ack — {@code targetService + ":" + salesOrderLineId} — with
 * {@code targetService = purchasing} for this handler.
 */
@Component
public class PurchaseOrderCancellationAppliedHandler extends AbstractInboxHandler<PurchaseOrderCancellationApplied> {

    public static final String HANDLER_NAME = "sales.compensation-purchasing-ack";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderCompensationEmitter compensationEmitter;

    public PurchaseOrderCancellationAppliedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderCompensationEmitter compensationEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, PurchaseOrderCancellationApplied.class,
            PurchaseOrderCancellationApplied.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.compensationEmitter = compensationEmitter;
    }

    @Override
    protected void apply(PurchaseOrderCancellationApplied payload, EventEnvelope envelope) {
        String legId = OrderPeggedSupplyCancellationRequested.TARGET_SERVICE_PURCHASING
            + ":" + payload.sourceSalesOrderLineId();
        String newState = sagaManager.applyCompensationAck(
            payload.sourceSalesOrderHeaderId(), legId, !payload.compensated());

        if (COMPENSATED.equals(newState)) {
            compensationEmitter.emitCompensated(payload.sourceSalesOrderHeaderId());
        } else if (COMPENSATION_FAILED.equals(newState)) {
            compensationEmitter.emitCompensationFailed(payload.sourceSalesOrderHeaderId());
        }

        log.info("[{}] processed {} ({}) for sales_order={} leg={} (compensated={}) → {}",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(),
            payload.sourceSalesOrderHeaderId(), legId, payload.compensated(), newState);
    }
}
