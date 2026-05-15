package com.northwood.testharness.inmemory.purchasing;

import com.northwood.purchasing.domain.PurchaseRequisition;
import com.northwood.purchasing.domain.PurchaseRequisitionId;
import com.northwood.purchasing.domain.PurchaseRequisitionRepository;
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

public final class InMemoryPurchaseRequisitionRepository implements PurchaseRequisitionRepository {

    private final Map<UUID, PurchaseRequisition> store = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryPurchaseRequisitionRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<PurchaseRequisition> findById(PurchaseRequisitionId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public List<PurchaseRequisition> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void save(PurchaseRequisition requisition) {
        store.put(requisition.id().value(), requisition);
        for (DomainEvent event : requisition.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    PurchaseRequisition.AGGREGATE_TYPE,
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
