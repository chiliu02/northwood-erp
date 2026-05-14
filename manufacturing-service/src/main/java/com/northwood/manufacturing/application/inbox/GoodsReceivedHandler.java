package com.northwood.manufacturing.application.inbox;

import static com.northwood.manufacturing.domain.saga.MakeToOrderSaga.RAW_MATERIAL_SHORTAGE;
import static com.northwood.manufacturing.domain.saga.MakeToOrderSaga.WORK_ORDER_CREATED;

import com.northwood.manufacturing.application.saga.MakeToOrderSagaManager;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.manufacturing.application.saga.MakeToOrderShortageRecoveryQueryPort;
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
 * un-park (saga transitions back to {@code work_order_created}, picked up
 * next worker tick) or narrow the stashed shortage.
 */
@Component
public class GoodsReceivedHandler extends AbstractInboxHandler<GoodsReceived> {

    public static final String CONSUMER_NAME = "manufacturing.make-to-order.goods-received";

    private final MakeToOrderSagaManager sagaManager;
    private final MakeToOrderShortageRecoveryQueryPort recovery;

    public GoodsReceivedHandler(
        InboxPort inbox,
        MakeToOrderSagaManager sagaManager,
        MakeToOrderShortageRecoveryQueryPort recovery,
        ObjectMapper json
    ) {
        super(inbox, json, GoodsReceived.class, GoodsReceived.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.recovery = recovery;
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
            String newState = sagaManager.unparkOrNarrowShortage(sagaId, receivedByProduct);
            if (WORK_ORDER_CREATED.equals(newState)) {
                unparkedCount++;
            } else if (RAW_MATERIAL_SHORTAGE.equals(newState)) {
                narrowedCount++;
            }
        }

        log.info("[{}] processed receipt {} for purchase_order={}; unparked={}, narrowed={}",
            CONSUMER_NAME, payload.goodsReceiptNumber(),
            payload.purchaseOrderHeaderId(), unparkedCount, narrowedCount);
    }
}
