package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.reporting.application.inbox.PurchaseOrderTrackingProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class GoodsReceivedHandler extends AbstractInboxHandler<GoodsReceived> {

    public static final String CONSUMER_NAME = "reporting.po-tracking.goods-received";

    private final PurchaseOrderTrackingProjection projection;
    private final ProductionPlanningProjection planning;

    public GoodsReceivedHandler(
        InboxPort inbox,
        PurchaseOrderTrackingProjection projection,
        ProductionPlanningProjection planning,
        ObjectMapper json
    ) {
        super(inbox, json, GoodsReceived.class, GoodsReceived.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
        this.planning = planning;
    }

    @Override
    protected void apply(GoodsReceived payload, EventEnvelope envelope) {
        BigDecimal receivedDelta = BigDecimal.ZERO;
        if (payload.lines() != null) {
            for (GoodsReceived.ReceivedLine line : payload.lines()) {
                BigDecimal qty = line.receivedQuantity() == null ? BigDecimal.ZERO : line.receivedQuantity();
                BigDecimal cost = line.unitCost() == null ? BigDecimal.ZERO : line.unitCost();
                receivedDelta = receivedDelta.add(qty.multiply(cost));
            }
        }
        projection.recordGoodsReceived(
            payload.purchaseOrderHeaderId(),
            payload.aggregateId(),
            receivedDelta,
            payload.occurredAt(),
            envelope.actorUserId()
        );
        // When a PO transitions to (or away from) 'received', recompute
        // open_purchase_orders_count for the source WO. Look up the source
        // from the just-updated tracking row — null means the PO wasn't
        // shortage-driven so the planning-board count doesn't apply.
        projection.findSourceWorkOrderForPo(payload.purchaseOrderHeaderId())
            .ifPresent(woId -> {
                int count = projection.countOpenForWorkOrder(woId);
                planning.setOpenPoCount(woId, count, payload.occurredAt());
            });
    }
}
