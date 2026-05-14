package com.northwood.testharness.inmemory.manufacturing;

import com.northwood.manufacturing.domain.WorkOrder;
import com.northwood.manufacturing.domain.WorkOrderId;
import com.northwood.manufacturing.domain.WorkOrderRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory {@link WorkOrderRepository}. Drains pending events on save.
 * Auxiliary count/find queries are minimally implemented — sufficient for
 * the harness tests that have shipped so far.
 */
public final class InMemoryWorkOrderRepository implements WorkOrderRepository {

    private final Map<UUID, WorkOrder> store = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryWorkOrderRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /** Test-side: seed a WO directly without going through release(). */
    public void seed(WorkOrder workOrder) {
        store.put(workOrder.id().value(), workOrder);
    }

    @Override
    public Optional<WorkOrder> findById(WorkOrderId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public void save(WorkOrder workOrder) {
        store.put(workOrder.id().value(), workOrder);
        for (DomainEvent event : workOrder.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    "WorkOrder",
                    event.aggregateId(),
                    event.eventType(),
                    event.eventVersion(),
                    json.writeValueAsString(event),
                    null, null, null, null
                ));
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialise " + event.eventType(), e);
            }
        }
    }

    @Override
    public int countUnfinishedChildren(UUID parentWorkOrderId) {
        return (int) store.values().stream()
            .filter(w -> parentWorkOrderId.equals(w.parentWorkOrderId()))
            .filter(w -> !isTerminal(w.status()))
            .count();
    }

    @Override
    public int countUnfinishedChildrenExcluding(UUID parentWorkOrderId, UUID excludeChildId) {
        return (int) store.values().stream()
            .filter(w -> parentWorkOrderId.equals(w.parentWorkOrderId()))
            .filter(w -> !w.id().value().equals(excludeChildId))
            .filter(w -> !isTerminal(w.status()))
            .count();
    }

    @Override
    public List<CompletedChild> findCompletedChildren(UUID parentWorkOrderId) {
        List<CompletedChild> out = new ArrayList<>();
        for (WorkOrder w : store.values()) {
            if (parentWorkOrderId.equals(w.parentWorkOrderId()) && "completed".equals(w.status())) {
                out.add(new CompletedChild(w.id().value(), w.finishedProductId(), w.completedQuantity()));
            }
        }
        return out;
    }

    @Override
    public List<UUID> findActiveIdsForSalesOrder(UUID salesOrderHeaderId) {
        List<UUID> out = new ArrayList<>();
        for (WorkOrder w : store.values()) {
            if (salesOrderHeaderId.equals(w.salesOrderHeaderId()) && !isTerminal(w.status())) {
                out.add(w.id().value());
            }
        }
        return out;
    }

    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "closed".equals(status) || "cancelled".equals(status);
    }
}
