package com.northwood.reporting.application.inbox.shortage;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.MaterialShortageProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import org.springframework.stereotype.Component;

@Component("shortage_ShortageDetectedHandler")
public class ShortageDetectedHandler extends AbstractInboxHandler<RawMaterialShortageDetected> {

    public static final String HANDLER_NAME = "reporting.material-shortage.shortage-detected";

    private final MaterialShortageProjection projection;

    public ShortageDetectedHandler(
        InboxPort inbox,
        MaterialShortageProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialShortageDetected.class, RawMaterialShortageDetected.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(RawMaterialShortageDetected payload, EventEnvelope envelope) {
        if (payload.components() == null) return;
        for (var c : payload.components()) {
            projection.recordShortageComponent(
                c.componentProductId(),
                c.componentSku(),
                c.componentName(),
                c.shortageQuantity(),
                payload.occurredAt()
            );
        }
    }
}
