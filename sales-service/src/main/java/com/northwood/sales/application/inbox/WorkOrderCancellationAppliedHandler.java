package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;
import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATION_FAILED;

import com.northwood.inventory.domain.events.OrderPeggedSupplyCancellationRequested;
import com.northwood.manufacturing.domain.events.WorkOrderCancellationApplied;
import com.northwood.sales.application.SalesOrderCompensationEmitter;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code manufacturing.WorkOrderCancellationApplied} — the
 * manufacturing leg of multi-leg sales-order compensation. Drains the leg
 * ({@code manufacturing:<salesOrderLineId>}) from the fulfilment saga's
 * outstanding-compensation set; {@code compensated = false} records the leg as an
 * un-compensatable leaf so the saga reaches {@code compensation_failed} instead of
 * {@code compensated}. When the drain empties the set, the saga reaches its
 * terminal and this handler emits the matching {@code sales.SalesOrderCompensated}
 * / {@code sales.SalesOrderCompensationFailed}.
 *
 * <p>Twin of {@link PurchaseOrderCancellationAppliedHandler} — the leg id is formed
 * the same way inventory enumerated it ({@code targetService + ":" + salesOrderLineId}),
 * with {@code targetService = manufacturing} for this handler.
 */
@Component
public class WorkOrderCancellationAppliedHandler extends AbstractInboxHandler<WorkOrderCancellationApplied> {

    public static final String HANDLER_NAME = "sales.compensation-manufacturing-ack";

    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderCompensationEmitter compensationEmitter;

    public WorkOrderCancellationAppliedHandler(
        InboxPort inbox,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderCompensationEmitter compensationEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderCancellationApplied.class,
            WorkOrderCancellationApplied.EVENT_TYPE, HANDLER_NAME);
        this.sagaManager = sagaManager;
        this.compensationEmitter = compensationEmitter;
    }

    @Override
    protected void apply(WorkOrderCancellationApplied payload, EventEnvelope envelope) {
        String legId = OrderPeggedSupplyCancellationRequested.TARGET_SERVICE_MANUFACTURING
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
