package com.northwood.testharness.inmemory.sales;

import com.northwood.sales.domain.SalesOrder;
import com.northwood.sales.domain.SalesOrderId;
import com.northwood.sales.domain.SalesOrderRepository;
import com.northwood.shared.domain.DomainEvent;
import com.northwood.shared.application.outbox.OutboxPort;
import com.northwood.shared.application.outbox.OutboxRow;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * In-memory backing for {@link SalesOrderRepository}. Mirrors the Jdbc adapter's
 * contract: {@code save(order)} stores the aggregate AND drains
 * {@code pullPendingEvents()} into the outbox in the same call.
 */
public final class InMemorySalesOrderRepository implements SalesOrderRepository {

    private final Map<UUID, SalesOrder> store = new HashMap<>();
    private final OutboxPort outbox;
    private final ObjectMapper json;

    public InMemorySalesOrderRepository(OutboxPort outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public Optional<SalesOrder> findById(SalesOrderId id) {
        return Optional.ofNullable(store.get(id.value()));
    }

    @Override
    public void save(SalesOrder order) {
        store.put(order.id().value(), order);
        for (DomainEvent event : order.pullPendingEvents()) {
            try {
                outbox.appendPending(OutboxRow.pending(
                    event.eventId(),
                    "SalesOrder",
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
