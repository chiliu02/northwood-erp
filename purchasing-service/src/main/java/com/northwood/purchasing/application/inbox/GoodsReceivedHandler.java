package com.northwood.purchasing.application.inbox;

import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.purchasing.application.inbox.PurchaseOrderReceiptProjection.ReceiptLine;
import com.northwood.purchasing.application.inbox.PurchaseOrderReceiptProjection.ReceiptOutcome;
import com.northwood.purchasing.application.saga.PurchaseToPaySagaManager;
import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Inbox handler for {@code inventory.GoodsReceived}. Records each received
 * line into the receipt projection (which reclassifies the PO header status
 * from {@code 'sent'} to {@code 'partially_received'} or {@code 'received'}),
 * then asks the saga manager to advance the saga when fully received.
 */
@Component
public class GoodsReceivedHandler extends AbstractInboxHandler<GoodsReceived> {

    public static final String CONSUMER_NAME = "purchasing.p2p.goods-received";

    private final PurchaseToPaySagaManager sagaManager;
    private final PurchaseOrderReceiptProjection receiptProjection;

    public GoodsReceivedHandler(
        InboxPort inbox,
        PurchaseToPaySagaManager sagaManager,
        PurchaseOrderReceiptProjection receiptProjection,
        ObjectMapper json
    ) {
        super(inbox, json, GoodsReceived.class, GoodsReceived.EVENT_TYPE, CONSUMER_NAME);
        this.sagaManager = sagaManager;
        this.receiptProjection = receiptProjection;
    }

    @Override
    protected void apply(GoodsReceived payload, EventEnvelope envelope) {
        List<ReceiptLine> receiptLines = new ArrayList<>();
        for (GoodsReceived.ReceivedLine rl : payload.lines()) {
            if (rl.purchaseOrderLineId() == null) {
                log.warn("[{}] receipt line {} has no purchase_order_line_id; skipping match",
                    CONSUMER_NAME, rl.receiptLineId());
                continue;
            }
            receiptLines.add(new ReceiptLine(rl.purchaseOrderLineId(), rl.receivedQuantity()));
        }
        ReceiptOutcome outcome = receiptProjection.recordReceipt(payload.purchaseOrderHeaderId(), receiptLines);

        sagaManager.applyGoodsReceived(payload.purchaseOrderHeaderId(), outcome.fullyReceived());

        log.info("[{}] purchase_order={} → {} ({} line(s) received, fully={})",
            CONSUMER_NAME, payload.purchaseOrderHeaderId(),
            outcome.fullyReceived() ? PurchaseOrder.Status.RECEIVED.dbValue() : PurchaseOrder.Status.PARTIALLY_RECEIVED.dbValue(),
            payload.lines().size(), outcome.fullyReceived());
    }
}
