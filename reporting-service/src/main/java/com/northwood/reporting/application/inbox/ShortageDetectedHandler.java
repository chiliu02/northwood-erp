package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ShortageDetectedHandler extends AbstractInboxHandler<RawMaterialShortageDetected> {

    public static final String CONSUMER_NAME = "reporting.production-planning.shortage-detected";

    private final ProductionPlanningProjection projection;

    public ShortageDetectedHandler(
        InboxPort inbox,
        ProductionPlanningProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialShortageDetected.class, RawMaterialShortageDetected.EVENT_TYPE, CONSUMER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(RawMaterialShortageDetected payload, EventEnvelope envelope) {
        int count = payload.components() == null ? 0 : payload.components().size();
        String summary = payload.components() == null
            ? null
            : payload.components().stream()
                .map(c -> c.componentSku() + " (-" + c.shortageQuantity() + ")")
                .collect(Collectors.joining(", "));
        projection.recordShortageDetected(
            payload.workOrderId(),
            count,
            summary,
            payload.occurredAt()
        );
    }
}
