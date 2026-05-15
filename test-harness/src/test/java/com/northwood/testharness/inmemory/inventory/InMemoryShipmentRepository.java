package com.northwood.testharness.inmemory.inventory;

import com.northwood.inventory.domain.Shipment;
import com.northwood.inventory.domain.ShipmentId;
import com.northwood.inventory.domain.ShipmentRepository;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import com.northwood.shared.domain.DomainEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory backing for {@link ShipmentRepository}. Drains the aggregate's
 * pending events to the outbox on save, mirroring the Jdbc adapter.
 */
public final class InMemoryShipmentRepository implements ShipmentRepository {

    private final Map<UUID, Shipment> byId = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemoryShipmentRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<Shipment> findById(ShipmentId id) {
        return Optional.ofNullable(byId.get(id.value()));
    }

    @Override
    public List<Shipment> findAllHeaders() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public void save(Shipment shipment) {
        byId.put(shipment.id().value(), shipment);
        for (DomainEvent event : shipment.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    "Shipment",
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
