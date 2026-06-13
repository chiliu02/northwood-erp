package com.northwood.reporting.application.inbox;

import tools.jackson.databind.ObjectMapper;
import com.northwood.inventory.domain.events.RawMaterialsReserved;
import com.northwood.reporting.application.inbox.ProductionPlanningProjection;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import org.springframework.stereotype.Component;

@Component
public class RawMaterialsReservedHandler extends AbstractInboxHandler<RawMaterialsReserved> {

    public static final String HANDLER_NAME = "reporting.production-planning.raw-materials-reserved";

    private final ProductionPlanningProjection projection;

    public RawMaterialsReservedHandler(
        InboxPort inbox,
        ProductionPlanningProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialsReserved.class, RawMaterialsReserved.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(RawMaterialsReserved payload, EventEnvelope envelope) {
        projection.recordRawMaterialsReserved(
            payload.workOrderId(),
            payload.status(),
            payload.occurredAt()
        );
    }
}
