package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.domain.PurchaseOrder;
import com.northwood.purchasing.domain.PurchaseOrderId;
import com.northwood.purchasing.domain.PurchaseOrderRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public final class InMemoryPurchaseOrderRepository implements PurchaseOrderRepository {

    private final Map<UUID, PurchaseOrder> store = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryPurchaseOrderRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<PurchaseOrder> findById(PurchaseOrderId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public void save(PurchaseOrder purchaseOrder) {
        store.put(purchaseOrder.id().value(), purchaseOrder);
        for (DomainEvent event : purchaseOrder.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    "PurchaseOrder",
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
}
