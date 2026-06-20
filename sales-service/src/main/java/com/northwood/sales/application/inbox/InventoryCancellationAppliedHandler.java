package com.northwood.sales.application.inbox;

import static com.northwood.sales.domain.saga.SalesOrderFulfilmentSaga.COMPENSATED;

import com.northwood.inventory.domain.events.InventorySalesOrderCancellationApplied;
import com.northwood.sales.application.SalesOrderCompensationEmitter;
import com.northwood.sales.application.SalesOrderService;
import com.northwood.sales.application.saga.SalesOrderFulfilmentSagaManager;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.SalesOrderCancellationApplied} — phase 2 of
 * the two-phase cancel. Inventory emits this ack only when it confirmed the
 * cancellation (no line had shipped), so it is the signal to finalise:
 * {@link SalesOrderService#confirmCancellation} flips the order to
 * {@code cancelled}, then the manager advances the fulfilment saga to
 * {@code 'compensated'} and {@code sales.SalesOrderCompensated} is emitted via the
 * shared {@link SalesOrderCompensationEmitter}. If a shipment won the race this
 * ack never arrives, so the order rides the normal ship → invoice → pay path.
 */
@Component
public class InventoryCancellationAppliedHandler extends AbstractInboxHandler<InventorySalesOrderCancellationApplied> {

    public static final String HANDLER_NAME = "sales.compensation-inventory-ack";

    private final SalesOrderService salesOrderService;
    private final SalesOrderFulfilmentSagaManager sagaManager;
    private final SalesOrderCompensationEmitter compensationEmitter;

    public InventoryCancellationAppliedHandler(
        InboxPort inbox,
        SalesOrderService salesOrderService,
        SalesOrderFulfilmentSagaManager sagaManager,
        SalesOrderCompensationEmitter compensationEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, InventorySalesOrderCancellationApplied.class, InventorySalesOrderCancellationApplied.EVENT_TYPE, HANDLER_NAME);
        this.salesOrderService = salesOrderService;
        this.sagaManager = sagaManager;
        this.compensationEmitter = compensationEmitter;
    }

    @Override
    protected void apply(InventorySalesOrderCancellationApplied payload, EventEnvelope envelope) {
        salesOrderService.confirmCancellation(payload.aggregateId());
        // Form the leg ids the saga drains: <targetService>:<salesOrderLineId>.
        // Empty in the common path (nothing order-pegged) → the saga compensates
        // straight away; a non-empty set parks it in 'compensating'.
        Set<String> legIds = payload.legs().stream()
            .map(leg -> leg.targetService() + ":" + leg.salesOrderLineId())
            .collect(Collectors.toSet());
        String newState = sagaManager.applyInventoryCancellationApplied(payload.aggregateId(), legIds);
        if (COMPENSATED.equals(newState)) {
            compensationEmitter.emitCompensated(payload.aggregateId());
        }
        // 'compensating' emits no terminal event yet — each leg's *CancellationApplied
        // ack drains the set and the final ack emits the terminal (compensated /
        // compensation_failed) from its own handler.

        log.info("[{}] processed {} ({}) for sales_order={} ({} pegged leg(s))",
            HANDLER_NAME, envelope.eventType(), envelope.eventId(), payload.aggregateId(), legIds.size());
    }
}
