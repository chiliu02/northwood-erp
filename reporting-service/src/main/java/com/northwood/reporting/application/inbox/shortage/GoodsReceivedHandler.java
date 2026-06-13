package com.northwood.reporting.application.inbox.shortage;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.MaterialShortageProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.inventory.domain.events.GoodsReceived;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("shortage_GoodsReceivedHandler")
public class GoodsReceivedHandler extends AbstractInboxHandler<GoodsReceived> {

    public static final String HANDLER_NAME = "reporting.material-shortage.goods-received";

    private final MaterialShortageProjection projection;

    public GoodsReceivedHandler(
        InboxPort inbox,
        MaterialShortageProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, GoodsReceived.class, GoodsReceived.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(GoodsReceived payload, EventEnvelope envelope) {
        if (payload.lines() == null) return;
        for (var l : payload.lines()) {
            projection.recordReceivedLine(
                l.productId(),
                l.productSku(),
                l.productName(),
                l.receivedQuantity(),
                payload.occurredAt()
            );
        }
    }
}
