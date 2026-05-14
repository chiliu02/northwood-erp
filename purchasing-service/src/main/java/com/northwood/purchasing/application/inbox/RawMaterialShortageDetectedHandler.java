package com.northwood.purchasing.application.inbox;

import com.northwood.manufacturing.domain.events.RawMaterialShortageDetected;
import com.northwood.purchasing.application.PurchaseRequisitionService;
import com.northwood.purchasing.application.dto.RequisitionLineRequest;
import com.northwood.purchasing.application.dto.WorkOrderShortageCommand;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Idempotent inbox handler for {@code manufacturing.RawMaterialShortageDetected}.
 * Creates a {@code work_order_shortage} requisition for the missing components.
 *
 * <p>The dedupe is per-event ({@code event_id} + {@code consumer_name}) — if
 * the same shortage event redelivers, the requisition is created exactly once.
 * Phase 2 will pick the requisition up and convert it to a purchase order.
 */
@Component
public class RawMaterialShortageDetectedHandler extends AbstractInboxHandler<RawMaterialShortageDetected> {

    public static final String CONSUMER_NAME = "purchasing.shortage-to-requisition";

    private final PurchaseRequisitionService requisitions;

    public RawMaterialShortageDetectedHandler(
        InboxPort inbox,
        PurchaseRequisitionService requisitions,
        ObjectMapper json
    ) {
        super(inbox, json, RawMaterialShortageDetected.class, RawMaterialShortageDetected.EVENT_TYPE, CONSUMER_NAME);
        this.requisitions = requisitions;
    }

    @Override
    protected void apply(RawMaterialShortageDetected payload, EventEnvelope envelope) {
        if (payload.components() == null || payload.components().isEmpty()) {
            log.warn("[{}] shortage event {} for work_order={} carries no components; skipping",
                CONSUMER_NAME, payload.eventId(), payload.workOrderId());
            return;
        }

        List<RequisitionLineRequest> lines = new ArrayList<>();
        for (RawMaterialShortageDetected.ShortageComponent c : payload.components()) {
            lines.add(new RequisitionLineRequest(
                c.componentProductId(),
                c.componentSku(),
                c.componentName(),
                c.shortageQuantity(),
                null
            ));
        }

        String requisitionNumber = "PR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        requisitions.createForWorkOrderShortage(new WorkOrderShortageCommand(
            requisitionNumber,
            payload.workOrderId(),
            lines
        ));

        log.info("[{}] requisition {} created for work_order={} ({} component(s) short)",
            CONSUMER_NAME, requisitionNumber, payload.workOrderId(), lines.size());
    }
}
