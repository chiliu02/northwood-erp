package com.northwood.manufacturing.application;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.northwood.manufacturing.application.WorkOrderOperationService.WorkOrderNotFoundException;
import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.manufacturing.domain.events.WorkOrderPriorityChanged;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.application.security.CurrentUserAccessor;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §3.5: sets a work order's priority. The WO aggregate doesn't currently
 * model priority (no manufacturing decision flow consumes it), so this
 * is a pure CQRS read-side slice: the service validates the WO exists +
 * the priority is one of the allowed values, then writes a
 * {@code manufacturing.WorkOrderPriorityChanged} event straight to the
 * outbox. Reporting's production-planning-board projection consumes it
 * to update {@code priority}.
 *
 * <p>If a future feature needs the write side to know priority (e.g. a
 * scheduler that picks the next WO to release), bring priority onto the
 * aggregate then — the event shape stays the same.
 */
@Service
public class WorkOrderPrioritisationService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderPrioritisationService.class);

    private static final Set<String> ALLOWED_PRIORITIES =
        Set.of("low", "normal", "high", "urgent");

    private final WorkOrderRepository workOrders;
    private final OutboxPort outbox;
    private final ObjectMapper json;
    private final CurrentUserAccessor currentUser;

    public WorkOrderPrioritisationService(
        WorkOrderRepository workOrders,
        OutboxPort outbox,
        ObjectMapper json,
        CurrentUserAccessor currentUser
    ) {
        this.workOrders = workOrders;
        this.outbox = outbox;
        this.json = json;
        this.currentUser = currentUser;
    }

    @Transactional
    public void setPriority(UUID workOrderId, String priority, String reason) {
        if (!ALLOWED_PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException(
                "priority must be one of " + ALLOWED_PRIORITIES + ", got: " + priority);
        }

        if (workOrders.findById(WorkOrderId.of(workOrderId)).isEmpty()) {
            throw new WorkOrderNotFoundException(workOrderId.toString());
        }

        WorkOrderPriorityChanged event = new WorkOrderPriorityChanged(
            UUID.randomUUID(),
            workOrderId,
            priority,
            reason,
            Instant.now()
        );
        try {
            outbox.appendPending(OutboxRow.pending(
                event.eventId(),
                WorkOrder.AGGREGATE_TYPE,
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                json.writeValueAsString(event),
                null, null, null,
                currentUser.currentUsername().orElse(null)
            ));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialise " + WorkOrderPriorityChanged.EVENT_TYPE, e);
        }

        log.info("set priority of work_order={} to {} (reason={})", workOrderId, priority, reason);
    }
}
