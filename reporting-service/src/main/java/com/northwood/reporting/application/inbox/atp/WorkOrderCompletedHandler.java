package com.northwood.reporting.application.inbox.atp;

import tools.jackson.databind.ObjectMapper;
import com.northwood.reporting.application.inbox.AvailableToPromiseProjection;
import com.northwood.shared.application.messaging.AbstractInboxHandler;
import com.northwood.manufacturing.domain.events.WorkOrderManufacturingCompleted;
import com.northwood.shared.application.inbox.InboxPort;
import com.northwood.shared.application.messaging.EventEnvelope;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * ATP consumer of {@code manufacturing.WorkOrderManufacturingCompleted}.
 * The event carries {@code completedQuantity} but not the original
 * {@code plannedQuantity} — we approximate by clearing the same
 * completedQuantity from {@code incoming_from_production} on the
 * assumption that planned≈completed for the showcase. Truer accounting
 * would require carrying both quantities on the event.
 */
@Component("atp_WorkOrderCompletedHandler")
public class WorkOrderCompletedHandler extends AbstractInboxHandler<WorkOrderManufacturingCompleted> {

    public static final String HANDLER_NAME = "reporting.atp.work-order-completed";

    private final AvailableToPromiseProjection projection;

    public WorkOrderCompletedHandler(
        InboxPort inbox,
        AvailableToPromiseProjection projection,
        ObjectMapper json
    ) {
        super(inbox, json, WorkOrderManufacturingCompleted.class, WorkOrderManufacturingCompleted.EVENT_TYPE, HANDLER_NAME);
        this.projection = projection;
    }

    @Override
    protected void apply(WorkOrderManufacturingCompleted payload, EventEnvelope envelope) {
        // Skip sub-assembly children — their FG isn't shippable, and inventory
        // doesn't bump on_hand for them either (per CLAUDE.md). For the same
        // reason ATP shouldn't add their planned qty to on_hand either.
        if (payload.parentWorkOrderId() != null) return;
        BigDecimal completed = payload.completedQuantity() == null ? BigDecimal.ZERO : payload.completedQuantity();
        projection.recordWorkOrderCompleted(
            payload.finishedProductId(),
            payload.finishedProductSku(),
            completed,  // approximate planned == completed
            completed,
            payload.occurredAt()
        );
    }
}
