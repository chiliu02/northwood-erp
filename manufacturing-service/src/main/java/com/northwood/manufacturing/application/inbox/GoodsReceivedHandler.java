package com.northwood.manufacturing.application.inbox;

import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.RAW_MATERIAL_RESERVATION_REQUESTED;
import static com.northwood.manufacturing.domain.saga.WorkOrderSaga.RAW_MATERIAL_SHORTAGE;

import com.northwood.manufacturing.application.saga.RawMaterialReservationRequestEmitter;
import com.northwood.manufacturing.application.saga.WorkOrderSagaManager;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.manufacturing.application.saga.WorkOrderShortageRecoveryQueryPort;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.GoodsReceived}. Builds the per-product
 * received-quantity map, asks the recovery query port for candidate
 * shortage-parked sagas, and for each candidate calls the manager to either
 * un-park (saga transitions to {@code raw_material_reservation_requested}) or
 * narrow the stashed shortage.
 *
 * <p>On un-park this handler re-emits {@code RawMaterialReservationRequested}
 * via {@link RawMaterialReservationRequestEmitter} (the same emitter the worker
 * uses for the initial request) so inventory retries the reservation against the
 * now-restocked materials. Mirrors the sales saga's {@code ReplenishmentFulfilledHandler}
 * — the recovery lands straight at the reservation-requested state rather than
 * bouncing through {@code work_order_created} for the worker to re-emit.
 */
@Component
public class GoodsReceivedHandler extends AbstractInboxHandler<GoodsReceived> {

    public static final String CONSUMER_NAME = "manufacturing.make-to-order.goods-received";

    private final WorkOrderSagaManager sagaManager;
    private final WorkOrderShortageRecoveryQueryPort recovery;
    private final RawMaterialReservationRequestEmitter reservationEmitter;

    public GoodsReceivedHandler(
        InboxPort inbox,
        WorkOrderSagaManager sagaManager,
        WorkOrderShortageRecoveryQueryPort recovery,
        RawMaterialReservationRequestEmitter reservationEmitter,
        ObjectMapper json
    ) {
        super(inbox, json, GoodsReceived.class, GoodsReceived.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.recovery = recovery;
        this.reservationEmitter = reservationEmitter;
    }

    @Override
    protected void apply(GoodsReceived payload, EventEnvelope envelope) {
        Map<UUID, BigDecimal> receivedByProduct = new HashMap<>();
        for (GoodsReceived.ReceivedLine rl : payload.lines()) {
            receivedByProduct.merge(rl.productId(),
                rl.receivedQuantity() == null ? BigDecimal.ZERO : rl.receivedQuantity(),
                BigDecimal::add);
        }

        List<UUID> candidates = recovery.findShortageSagaIdsForReceivedProducts(receivedByProduct.keySet());

        int unparkedCount = 0;
        int narrowedCount = 0;
        for (UUID sagaId : candidates) {
            WorkOrderSagaManager.RecoveryOutcome outcome =
                sagaManager.unparkOrNarrowShortage(sagaId, receivedByProduct);
            if (RAW_MATERIAL_RESERVATION_REQUESTED.equals(outcome.state())) {
                // Shortage fully covered — re-request reservation against the
                // now-restocked materials (mirrors the worker's emission).
                reservationEmitter.emitFor(outcome.workOrderId());
                unparkedCount++;
            } else if (RAW_MATERIAL_SHORTAGE.equals(outcome.state())) {
                narrowedCount++;
            }
        }

        log.info("[{}] processed receipt {} for purchase_order={}; unparked={}, narrowed={}",
            CONSUMER_NAME, payload.goodsReceiptNumber(),
            payload.purchaseOrderHeaderId(), unparkedCount, narrowedCount);
    }
}
